package com.casaayg.place;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text.Description;
import com.casaayg.place.PlaceService.Cdp;
import com.casaayg.place.PlaceService.Kind;
import com.casaayg.place.PlaceService.NewTransfer;
import com.casaayg.place.PlaceService.PlaceView;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.space.Spaces;
import com.casaayg.transaction.TransactionService;
import com.casaayg.transaction.TransactionService.TransactionData;

/**
 * Renovación de un CDP. Cálculo como lo hace el banco (comprobado con un comprobante real):
 *   interés bruto = capital × tasa anual × plazo / base (360 o 365), redondeado a céntimos;
 *   impuesto      = bruto × % de impuesto, redondeado a 5 céntimos;
 *   interés neto  = bruto − impuesto (cae en la cuenta de pago).
 * El capital vuelve y se reinvierte junto con el aporte: eso se anota solo como el aporte
 * movido de la cuenta al CDP. El diezmo, si se pide, es el 10 % del bruto.
 *
 * Al renovar (decisión del 2026-11-26): el interés del plazo que terminó se calcula con los
 * datos que tenía el CDP en ese plazo; la tasa, el plazo, el impuesto y la base que se escriben
 * al renovar valen para el plazo que empieza. El capital se puede subir (aporte, desde la
 * cuenta de pago) o bajar (retiro, a la cuenta que se elija), sin dejar el CDP en cero:
 * para cerrarlo se saca el dinero y se archiva.
 */
@Service
public class CdpService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal FIVE_CENTS = new BigDecimal("0.05");

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final PlaceService places;
    private final TransactionService transactions;

    CdpService(JdbcClient jdbc, Spaces spaces, PlaceService places, TransactionService transactions) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.places = places;
        this.transactions = transactions;
    }

    public record CdpSettings(BigDecimal rate, int termDays, int dayBasis, BigDecimal taxPct, LocalDate maturesOn,
                              UUID payoutPlace, BigDecimal lastContribution) {}

    /**
     * Lo que se escribe al renovar. Lo que venga null toma el valor de la vez anterior
     * (el aporte, el último aporte; el retiro, cero).
     */
    public record RenewTerms(BigDecimal rate, Integer termDays, Integer dayBasis, BigDecimal taxPct,
                             BigDecimal contribution, BigDecimal withdrawal, UUID withdrawTo) {}

    /** Las condiciones de un plazo. */
    public record Terms(BigDecimal rate, int termDays, int dayBasis, BigDecimal taxPct) {}

    /**
     * {@code earned}: las condiciones del plazo que terminó (con ellas salen los intereses);
     * {@code next}: las del plazo que empieza.
     */
    public record Renewal(BigDecimal principal, Terms earned, BigDecimal grossInterest, BigDecimal tax,
                          BigDecimal netInterest, BigDecimal tithe, BigDecimal contribution, BigDecimal withdrawal,
                          UUID withdrawTo, BigDecimal newPrincipal, Terms next, LocalDate maturesOn,
                          LocalDate nextMaturity, UUID payoutPlace, Money.Currency currency) {}

    @Transactional
    public PlaceView configure(UUID spaceId, UUID placeId, CdpSettings s) {
        spaces.requireWritable(spaceId);
        PlaceView cdp = requireCdp(spaceId, placeId);
        if (s.payoutPlace().equals(placeId)) {
            throw new IllegalArgumentException("la cuenta de pago no puede ser el mismo CDP");
        }
        if (places.requirePlace(spaceId, s.payoutPlace(), false) != cdp.currency()) {
            throw new ConflictException("La cuenta donde caen los intereses tiene que ser de la misma moneda.");
        }
        BigDecimal contribution = s.lastContribution() == null ? null
            : new Money(s.lastContribution(), cdp.currency()).amount();
        jdbc.sql("""
                UPDATE place SET cdp_rate = ?, cdp_term_days = ?, cdp_day_basis = ?, cdp_tax_pct = ?,
                                 cdp_matures_on = ?, cdp_payout_place = ?, cdp_last_contribution = ?
                 WHERE id = ? AND space_id = ?""")
            .params(s.rate(), s.termDays(), s.dayBasis(), s.taxPct(), s.maturesOn(), s.payoutPlace(), contribution,
                placeId, spaceId)
            .update();
        return places.list(spaceId).stream().filter(p -> p.id().equals(placeId)).findFirst().orElseThrow();
    }

    /** Lo que pasaría al renovar hoy, sin anotar nada. */
    @Transactional(readOnly = true)
    public Renewal preview(UUID spaceId, UUID placeId, RenewTerms terms) {
        spaces.requireMember(spaceId);
        return compute(requireConfigured(spaceId, placeId), terms);
    }

    /**
     * Renueva un CDP vencido: intereses netos (con su diezmo) a la cuenta de pago, el aporte de la
     * cuenta al CDP o el retiro del CDP a la cuenta elegida, y las condiciones del plazo nuevo,
     * todo en una transacción. Solo se puede una vez por vencimiento.
     */
    @Transactional
    public Renewal renew(UUID spaceId, UUID placeId, RenewTerms terms, boolean tithe) {
        spaces.requireWritable(spaceId);
        PlaceView cdp = requireConfigured(spaceId, placeId);
        if (cdp.cdp().maturesOn().isAfter(LocalDate.now())) {
            throw new ConflictException("Este CDP todavía no vence.");
        }
        Renewal r = compute(cdp, terms);
        if (r.withdrawal().signum() > 0) {
            if (r.withdrawTo() == null) {
                throw new IllegalArgumentException("falta la cuenta a la que va el retiro");
            }
            if (r.withdrawTo().equals(placeId)) {
                throw new IllegalArgumentException("el retiro no puede ir al mismo CDP");
            }
            if (places.requirePlace(spaceId, r.withdrawTo(), false) != cdp.currency()) {
                throw new ConflictException("La cuenta del retiro tiene que ser de la misma moneda que el CDP.");
            }
        }
        UUID interests = transactions.ensureCategory(spaceId, "Intereses", "INGRESO");
        transactions.create(spaceId, new TransactionData(interests, r.maturesOn(),
            new Description("Intereses " + cdp.name()),
            new Money(r.netInterest(), cdp.currency()), null, r.payoutPlace(), r.grossInterest(), tithe));
        if (r.contribution().signum() > 0) {
            places.transfer(spaceId, new NewTransfer(r.payoutPlace(), placeId, r.maturesOn(), r.contribution(),
                "Aporte al " + cdp.name()));
        }
        if (r.withdrawal().signum() > 0) {
            places.transfer(spaceId, new NewTransfer(placeId, r.withdrawTo(), r.maturesOn(), r.withdrawal(),
                "Retiro del " + cdp.name()));
        }
        // El aporte guardado es la sugerencia de la próxima vez: un retiro no la borra.
        Terms n = r.next();
        jdbc.sql("""
                UPDATE place SET cdp_matures_on = ?, cdp_rate = ?, cdp_term_days = ?, cdp_day_basis = ?,
                                 cdp_tax_pct = ?, cdp_last_contribution = coalesce(?, cdp_last_contribution)
                 WHERE id = ? AND space_id = ?""")
            .params(r.nextMaturity(), n.rate(), n.termDays(), n.dayBasis(), n.taxPct(),
                r.withdrawal().signum() > 0 ? null : r.contribution(), placeId, spaceId)
            .update();
        return r;
    }

    static Renewal compute(PlaceView cdp, RenewTerms t) {
        Cdp c = cdp.cdp();
        Terms earned = new Terms(c.rate(), c.termDays(), c.dayBasis(), c.taxPct());
        Terms next = checkTerms(new Terms(
            t.rate() != null ? t.rate() : c.rate(),
            t.termDays() != null ? t.termDays() : c.termDays(),
            t.dayBasis() != null ? t.dayBasis() : c.dayBasis(),
            t.taxPct() != null ? t.taxPct() : c.taxPct()));
        BigDecimal withdrawal = new Money(t.withdrawal() != null ? t.withdrawal() : BigDecimal.ZERO,
            cdp.currency()).amount();
        BigDecimal contribution = t.contribution() != null ? t.contribution()
            : withdrawal.signum() > 0 || c.lastContribution() == null ? BigDecimal.ZERO : c.lastContribution();
        contribution = new Money(contribution, cdp.currency()).amount();
        if (contribution.signum() > 0 && withdrawal.signum() > 0) {
            throw new IllegalArgumentException("o se suma o se retira, no las dos");
        }
        BigDecimal principal = cdp.balance().max(BigDecimal.ZERO);
        BigDecimal newPrincipal = principal.add(contribution).subtract(withdrawal);
        if (newPrincipal.signum() <= 0) {
            throw new ConflictException(
                "El CDP no puede quedar en cero. Para cerrarlo, saca el dinero con una transferencia y archívalo.");
        }
        BigDecimal gross = grossInterest(principal, earned.rate(), earned.termDays(), earned.dayBasis());
        BigDecimal tax = tax(gross, earned.taxPct());
        BigDecimal net = gross.subtract(tax);
        BigDecimal tithe = gross.multiply(TransactionService.TITHE_RATE).setScale(2, RoundingMode.HALF_UP);
        return new Renewal(principal, earned, gross, tax, net, tithe, contribution, withdrawal, t.withdrawTo(),
            newPrincipal, next, c.maturesOn(), c.maturesOn().plusDays(next.termDays()), c.payoutPlace(),
            cdp.currency());
    }

    /** Los mismos límites que al configurar el CDP. */
    static Terms checkTerms(Terms t) {
        if (t.rate() == null || t.rate().signum() <= 0 || t.rate().compareTo(HUNDRED) >= 0
                || t.rate().stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("tasa fuera de rango");
        }
        if (t.termDays() < 1 || t.termDays() > 3660) {
            throw new IllegalArgumentException("plazo fuera de rango");
        }
        if (t.dayBasis() != 360 && t.dayBasis() != 365) {
            throw new IllegalArgumentException("base de días 360 o 365");
        }
        if (t.taxPct() == null || t.taxPct().signum() < 0 || t.taxPct().compareTo(HUNDRED) >= 0
                || t.taxPct().stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("impuesto fuera de rango");
        }
        return t;
    }

    static BigDecimal grossInterest(BigDecimal principal, BigDecimal ratePct, int termDays, int dayBasis) {
        return principal.multiply(ratePct).multiply(BigDecimal.valueOf(termDays))
            .divide(HUNDRED.multiply(BigDecimal.valueOf(dayBasis)), 2, RoundingMode.HALF_UP);
    }

    /** El banco redondea el impuesto a 5 céntimos (801,5625 → 801,55). */
    static BigDecimal tax(BigDecimal gross, BigDecimal taxPct) {
        BigDecimal raw = gross.multiply(taxPct).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        return raw.divide(FIVE_CENTS, 0, RoundingMode.HALF_UP).multiply(FIVE_CENTS).setScale(2, RoundingMode.UNNECESSARY);
    }

    private PlaceView requireCdp(UUID spaceId, UUID placeId) {
        PlaceView place = places.list(spaceId).stream().filter(p -> p.id().equals(placeId)).findFirst()
            .orElseThrow(com.casaayg.shared.web.NotFoundException::new);
        if (place.kind() != Kind.CDP || place.archived()) {
            throw new IllegalArgumentException("no es un CDP activo");
        }
        return place;
    }

    private PlaceView requireConfigured(UUID spaceId, UUID placeId) {
        PlaceView cdp = requireCdp(spaceId, placeId);
        Cdp c = cdp.cdp();
        if (c == null || c.rate() == null || c.termDays() == null || c.dayBasis() == null || c.taxPct() == null
                || c.maturesOn() == null || c.payoutPlace() == null) {
            throw new ConflictException("Primero configura la tasa, el vencimiento y la cuenta de pago del CDP.");
        }
        return cdp;
    }
}

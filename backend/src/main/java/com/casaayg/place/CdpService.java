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

    public record Renewal(BigDecimal principal, BigDecimal rate, BigDecimal grossInterest, BigDecimal tax,
                          BigDecimal netInterest, BigDecimal tithe, BigDecimal contribution, BigDecimal newPrincipal,
                          LocalDate maturesOn, LocalDate nextMaturity, UUID payoutPlace, Money.Currency currency) {}

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
    public Renewal preview(UUID spaceId, UUID placeId, BigDecimal rate, BigDecimal contribution) {
        spaces.requireMember(spaceId);
        return compute(requireConfigured(spaceId, placeId), rate, contribution);
    }

    /**
     * Renueva un CDP vencido: intereses netos (con su diezmo) a la cuenta de pago y el aporte de la
     * cuenta al CDP, todo en una transacción. Solo se puede una vez por vencimiento.
     */
    @Transactional
    public Renewal renew(UUID spaceId, UUID placeId, BigDecimal rate, BigDecimal contribution, boolean tithe) {
        spaces.requireWritable(spaceId);
        PlaceView cdp = requireConfigured(spaceId, placeId);
        if (cdp.cdp().maturesOn().isAfter(LocalDate.now())) {
            throw new ConflictException("Este CDP todavía no vence.");
        }
        Renewal r = compute(cdp, rate, contribution);
        UUID interests = transactions.ensureCategory(spaceId, "Intereses", "INGRESO");
        transactions.create(spaceId, new TransactionData(interests, r.maturesOn(),
            new Description("Intereses " + cdp.name()),
            new Money(r.netInterest(), cdp.currency()), null, r.payoutPlace(), r.grossInterest(), tithe));
        if (r.contribution().signum() > 0) {
            places.transfer(spaceId, new NewTransfer(r.payoutPlace(), placeId, r.maturesOn(), r.contribution(),
                "Aporte al " + cdp.name()));
        }
        jdbc.sql("""
                UPDATE place SET cdp_matures_on = ?, cdp_rate = ?, cdp_last_contribution = ?
                 WHERE id = ? AND space_id = ?""")
            .params(r.nextMaturity(), r.rate(), r.contribution(), placeId, spaceId)
            .update();
        return r;
    }

    static Renewal compute(PlaceView cdp, BigDecimal rateOverride, BigDecimal contributionOverride) {
        Cdp c = cdp.cdp();
        BigDecimal rate = rateOverride != null ? rateOverride : c.rate();
        if (rate.signum() <= 0 || rate.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException("tasa fuera de rango");
        }
        BigDecimal contribution = contributionOverride != null ? contributionOverride
            : c.lastContribution() != null ? c.lastContribution() : BigDecimal.ZERO;
        contribution = new Money(contribution, cdp.currency()).amount();
        BigDecimal principal = cdp.balance().max(BigDecimal.ZERO);
        BigDecimal gross = grossInterest(principal, rate, c.termDays(), c.dayBasis());
        BigDecimal tax = tax(gross, c.taxPct());
        BigDecimal net = gross.subtract(tax);
        BigDecimal tithe = gross.multiply(TransactionService.TITHE_RATE).setScale(2, RoundingMode.HALF_UP);
        return new Renewal(principal, rate, gross, tax, net, tithe, contribution, principal.add(contribution),
            c.maturesOn(), c.maturesOn().plusDays(c.termDays()), c.payoutPlace(), cdp.currency());
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
        if (c == null || c.rate() == null || c.maturesOn() == null || c.payoutPlace() == null) {
            throw new ConflictException("Primero configura la tasa, el vencimiento y la cuenta de pago del CDP.");
        }
        return cdp;
    }
}

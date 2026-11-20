package com.casaayg.household;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text.Description;
import com.casaayg.place.PlaceService;
import com.casaayg.place.PlaceService.NewTransfer;
import com.casaayg.push.PushNotice;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;
import com.casaayg.transaction.TransactionService;
import com.casaayg.transaction.TransactionService.TransactionData;

/**
 * La casa al casarse (V11): "lo tuyo, lo mío y lo nuestro". Todo el dinero entra a la casa;
 * cada quien recibe su dinero personal (piso + bono de lo que sobró) al cerrar el mes, y puede
 * aportar de lo suyo. De la pareja solo se ve el saldo personal total.
 */
@Service
public class HouseholdService {

    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final PlaceService places;
    private final TransactionService transactions;
    private final ApplicationEventPublisher events;

    HouseholdService(JdbcClient jdbc, Spaces spaces, PlaceService places, TransactionService transactions,
                     ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.places = places;
        this.transactions = transactions;
        this.events = events;
    }

    public record Partner(UUID userId, String displayName, BigDecimal crc, BigDecimal usd, boolean me) {}

    public record Settings(BigDecimal personalFloor, BigDecimal bonusPct) {}

    public record Closed(int year, int month, BigDecimal surplus, BigDecimal bonusPct, BigDecimal perPerson) {}

    /** {@code since}: el mes en que se creó la casa ("2026-11"); antes de eso no hay qué cerrar. */
    public record Household(Settings settings, List<Partner> partners, UUID myDestination, UUID myPersonalSpace,
                            List<Closed> closes, String since) {}

    /** Lo que pasaría al cerrar un mes. {@code rest}: lo que queda en la casa después de pagar. */
    public record ClosePreview(int year, int month, BigDecimal income, BigDecimal expenses, BigDecimal surplus,
                               BigDecimal personalFloor, BigDecimal bonusPct, BigDecimal bonus, BigDecimal perPerson,
                               int members, BigDecimal rest, Closed closed) {}

    @Transactional(readOnly = true)
    public Household household(UUID familyId) {
        requireFamily(familyId, false);
        UUID me = CurrentUser.require();
        List<Partner> partners = jdbc.sql("SELECT user_id, display_name, crc, usd FROM partner_balances(?)")
            .param(familyId)
            .query((rs, n) -> new Partner(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), me.equals(rs.getObject(1, UUID.class))))
            .list();
        UUID destination = jdbc.sql("SELECT place_id FROM personal_destination WHERE user_id = current_app_user()")
            .query(UUID.class).optional().orElse(null);
        List<Closed> closes = jdbc.sql("""
                SELECT year, month, surplus, bonus_pct, per_person FROM month_close
                 WHERE space_id = ? ORDER BY year DESC, month DESC LIMIT 12""")
            .param(familyId)
            .query((rs, n) -> new Closed(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)))
            .list();
        return new Household(settings(familyId), partners, destination, myPersonalSpace(), closes, since(familyId).toString());
    }

    @Transactional
    public Settings updateSettings(UUID familyId, BigDecimal floor, BigDecimal bonusPct) {
        requireFamily(familyId, true);
        BigDecimal f = new Money(floor, Money.Currency.CRC).amount();
        if (bonusPct.signum() < 0 || bonusPct.compareTo(BigDecimal.valueOf(5)) > 0 || bonusPct.scale() > 1) {
            throw new IllegalArgumentException("bono entre 0 y 5 %");
        }
        jdbc.sql("""
                INSERT INTO household_settings (space_id, personal_floor, bonus_pct) VALUES (?, ?, ?)
                ON CONFLICT (space_id) DO UPDATE SET personal_floor = EXCLUDED.personal_floor,
                                                     bonus_pct = EXCLUDED.bonus_pct, updated_at = now()""")
            .params(familyId, f, bonusPct).update();
        return settings(familyId);
    }

    /** Dónde recibo mi dinero personal: un lugar en colones de MI espacio personal (null = ninguno). */
    @Transactional
    public void setDestination(UUID placeId) {
        UUID personal = myPersonalSpace();
        if (personal == null) {
            throw new NotFoundException();
        }
        jdbc.sql("DELETE FROM personal_destination WHERE user_id = current_app_user()").update();
        if (placeId == null) {
            return;
        }
        if (places.requirePlace(personal, placeId, false) != Money.Currency.CRC) {
            throw new ConflictException("El dinero personal llega en colones: elige una cuenta en colones.");
        }
        jdbc.sql("INSERT INTO personal_destination (user_id, space_id, place_id) VALUES (?, ?, ?)")
            .params(CurrentUser.require(), personal, placeId).update();
    }

    /** Aporte al hogar: de un lugar MÍO a uno de la casa. Gasto en lo personal, ingreso en la casa. */
    @Transactional
    public void contribute(UUID familyId, UUID fromPlace, UUID toPlace, BigDecimal amount, LocalDate date) {
        requireFamily(familyId, true);
        UUID personal = myPersonalSpace();
        if (personal == null) {
            throw new NotFoundException();
        }
        Money.Currency from = places.requirePlace(personal, fromPlace, false);
        Money.Currency to = places.requirePlace(familyId, toPlace, false);
        if (from != to) {
            throw new ConflictException("El aporte va entre cuentas de la misma moneda.");
        }
        Money money = new Money(amount, from);
        if (money.amount().signum() <= 0) {
            throw new IllegalArgumentException("monto en cero");
        }
        String name = myName();
        transactions.create(personal, new TransactionData(
            transactions.ensureCategory(personal, "Aporte al hogar", "NECESIDAD"), date,
            new Description("Aporte al hogar"), money, null, fromPlace));
        transactions.create(familyId, new TransactionData(
            transactions.ensureCategory(familyId, "Aportes", "INGRESO"), date,
            new Description("Aporte de " + name), money, null, toPlace));
    }

    /**
     * Reabrir un mes cerrado: borra lo que el cierre creó (en la casa y en lo personal de cada
     * quien) y el mes vuelve a quedar abierto. Solo el último mes cerrado.
     */
    @Transactional
    public void reopen(UUID familyId, YearMonth month) {
        requireFamily(familyId, true);
        Integer latest = jdbc.sql("""
                SELECT year * 100 + month FROM month_close WHERE space_id = ? ORDER BY year DESC, month DESC LIMIT 1""")
            .param(familyId).query(Integer.class).optional().orElse(null);
        int wanted = month.getYear() * 100 + month.getMonthValue();
        if (latest == null || latest < wanted) {
            throw new NotFoundException();
        }
        if (latest > wanted) {
            throw new ConflictException("Solo se puede reabrir el último mes cerrado.");
        }
        jdbc.sql("SELECT household_reopen(?, ?::smallint, ?::smallint)")
            .params(familyId, month.getYear(), month.getMonthValue()).query().singleValue();
        tellPartner(familyId, myName() + " reabrió " + monthName(month) + ": se deshizo el dinero personal de ese mes.");
    }

    /** Aviso a la pareja (no a quien lo hizo), después de confirmar. Sin montos. */
    private void tellPartner(UUID familyId, String text) {
        List<UUID> others = jdbc.sql("SELECT user_id FROM space_member WHERE space_id = ? AND user_id <> current_app_user()")
            .param(familyId).query(UUID.class).list();
        if (!others.isEmpty()) {
            events.publishEvent(new PushNotice(others, "La casa", text, "/casa"));
        }
    }

    private static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("es-CR"));
    }

    private void link(UUID familyId, YearMonth month, UUID txId, UUID transferId) {
        jdbc.sql("INSERT INTO month_close_item (space_id, year, month, tx_id, transfer_id) VALUES (?, ?, ?, ?, ?)")
            .params(familyId, month.getYear(), month.getMonthValue(), txId, transferId).update();
    }

    private YearMonth since(UUID familyId) {
        return YearMonth.from(jdbc.sql("SELECT created_at FROM space WHERE id = ?").param(familyId)
            .query(java.time.OffsetDateTime.class).single().atZoneSameInstant(COSTA_RICA));
    }

    @Transactional(readOnly = true)
    public ClosePreview preview(UUID familyId, YearMonth month, BigDecimal bonusPctOverride) {
        requireFamily(familyId, false);
        return compute(familyId, month, bonusPctOverride);
    }

    /**
     * Cerrar el mes: a cada miembro, piso + bono (igual para todos), como gasto de la casa desde
     * {@code fromPlace} e ingreso en su espacio personal; lo que sobra puede ir a otro lugar de la
     * casa (una meta, el fondo de emergencia). Una sola vez por mes.
     */
    @Transactional
    public ClosePreview close(UUID familyId, YearMonth month, BigDecimal bonusPct, UUID fromPlace, UUID restPlace) {
        requireFamily(familyId, true);
        if (month.isAfter(YearMonth.now(COSTA_RICA))) {
            throw new IllegalArgumentException("ese mes todavía no empieza");
        }
        if (places.requirePlace(familyId, fromPlace, false) != Money.Currency.CRC
                || (restPlace != null && places.requirePlace(familyId, restPlace, false) != Money.Currency.CRC)) {
            throw new ConflictException("El cierre del mes se hace en colones: elige cuentas en colones.");
        }
        if (month.isBefore(since(familyId))) {
            throw new ConflictException("La casa todavía no existía ese mes.");
        }
        ClosePreview p = compute(familyId, month, bonusPct);
        if (p.closed() != null) {
            throw new ConflictException("Ese mes ya está cerrado.");
        }
        if (p.perPerson().signum() <= 0) {
            throw new ConflictException("Este mes no le toca nada a nadie: pongan primero el monto fijo en los ajustes.");
        }
        LocalDate today = LocalDate.now(COSTA_RICA);
        LocalDate date = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        String label = month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("es-CR"));
        try {
            jdbc.sql("""
                    INSERT INTO month_close (space_id, year, month, closed_by, surplus, bonus_pct, per_person)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(familyId, month.getYear(), month.getMonthValue(), CurrentUser.require(), p.surplus(),
                    p.bonusPct(), p.perPerson())
                .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Ese mes ya está cerrado.");
        }
        if (p.perPerson().signum() > 0) {
            UUID category = transactions.ensureCategory(familyId, "Dinero personal", "NECESIDAD");
            List<Partner> members = jdbc.sql("SELECT user_id, display_name, crc, usd FROM partner_balances(?)")
                .param(familyId)
                .query((rs, n) -> new Partner(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                    rs.getBigDecimal(4), false))
                .list();
            for (Partner m : members) {
                UUID expense = transactions.create(familyId, new TransactionData(category, date,
                    new Description("Dinero personal de " + m.displayName() + " (" + label + ")"),
                    new Money(p.perPerson(), Money.Currency.CRC), null, fromPlace)).id();
                link(familyId, month, expense, null);
                jdbc.sql("SELECT household_pay_personal(?, ?, ?, ?, ?, ?::smallint, ?::smallint)")
                    .params(familyId, m.userId(), p.perPerson(), date, "Dinero personal de " + label,
                        month.getYear(), month.getMonthValue())
                    .query().singleValue();
            }
        }
        if (restPlace != null && !restPlace.equals(fromPlace) && p.rest().signum() > 0) {
            UUID transfer = places.transfer(familyId,
                new NewTransfer(fromPlace, restPlace, date, p.rest(), "Lo que sobró de " + label)).id();
            link(familyId, month, null, transfer);
        }
        tellPartner(familyId, myName() + " cerró " + label + ": ya te llegó tu dinero personal.");
        return compute(familyId, month, bonusPct);
    }

    ClosePreview compute(UUID familyId, YearMonth month, BigDecimal bonusOverride) {
        record Flow(BigDecimal income, BigDecimal expenses) {}
        Flow flow = jdbc.sql("""
                SELECT COALESCE(sum(t.amount) FILTER (WHERE c.category_group = 'INGRESO'), 0),
                       COALESCE(sum(t.amount) FILTER (WHERE c.category_group <> 'INGRESO'), 0)
                  FROM transaction t JOIN category c ON c.id = t.category_id
                 WHERE t.space_id = ? AND t.currency = 'CRC' AND t.tx_date >= ? AND t.tx_date < ?""")
            .params(familyId, month.atDay(1), month.plusMonths(1).atDay(1))
            .query((rs, n) -> new Flow(rs.getBigDecimal(1), rs.getBigDecimal(2))).single();
        Closed closed = jdbc.sql("""
                SELECT year, month, surplus, bonus_pct, per_person FROM month_close
                 WHERE space_id = ? AND year = ? AND month = ?""")
            .params(familyId, month.getYear(), month.getMonthValue())
            .query((rs, n) -> new Closed(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)))
            .optional().orElse(null);
        Settings s = settings(familyId);
        BigDecimal pct = bonusOverride != null ? bonusOverride : s.bonusPct();
        if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(5)) > 0) {
            throw new IllegalArgumentException("bono entre 0 y 5 %");
        }
        int members = jdbc.sql("SELECT count(*) FROM space_member WHERE space_id = ?").param(familyId)
            .query(Integer.class).single();
        return calculate(month, flow.income(), flow.expenses(), s.personalFloor(), pct, members, closed);
    }

    /** El cálculo, sin BD: bono = % de lo que sobró (a colones enteros), igual para cada miembro. */
    static ClosePreview calculate(YearMonth month, BigDecimal income, BigDecimal expenses, BigDecimal floor,
                                  BigDecimal pct, int members, Closed closed) {
        BigDecimal surplus = income.subtract(expenses);
        BigDecimal bonus = surplus.signum() > 0
            ? surplus.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal perPerson = floor.add(bonus).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rest = surplus.subtract(perPerson.multiply(BigDecimal.valueOf(members))).max(BigDecimal.ZERO);
        return new ClosePreview(month.getYear(), month.getMonthValue(), income, expenses, surplus, floor, pct,
            bonus, perPerson, members, rest, closed);
    }

    private Settings settings(UUID familyId) {
        return jdbc.sql("SELECT personal_floor, bonus_pct FROM household_settings WHERE space_id = ?").param(familyId)
            .query((rs, n) -> new Settings(rs.getBigDecimal(1), rs.getBigDecimal(2)))
            .optional().orElse(new Settings(BigDecimal.ZERO, new BigDecimal("2.5")));
    }

    /** Un espacio familiar del que soy miembro (si no, 404, igual que si no existiera). */
    private void requireFamily(UUID familyId, boolean write) {
        if (write) {
            spaces.requireWritable(familyId);
        } else {
            spaces.requireMember(familyId);
        }
        boolean family = jdbc.sql("SELECT kind = 'FAMILIA' FROM space WHERE id = ?").param(familyId)
            .query(Boolean.class).single();
        if (!family) {
            throw new NotFoundException();
        }
    }

    private UUID myPersonalSpace() {
        return jdbc.sql("""
                SELECT s.id FROM space s JOIN space_member m ON m.space_id = s.id
                 WHERE m.user_id = current_app_user() AND s.kind = 'PERSONAL' AND s.archived_at IS NULL
                 ORDER BY s.created_at LIMIT 1""")
            .query(UUID.class).optional().orElse(null);
    }

    private String myName() {
        return jdbc.sql("SELECT display_name FROM app_user WHERE id = current_app_user()").query(String.class).single();
    }
}

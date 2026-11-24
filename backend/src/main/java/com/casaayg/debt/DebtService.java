package com.casaayg.debt;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

/**
 * Deudas (V22, V23): lo que se le debe a una persona. Lo que falta = monto − pagado antes −
 * perdonado − abonos; los abonos son movimientos ligados (los anota TransactionService). La BD
 * repite cada regla en triggers.
 */
@Service
public class DebtService {

    public static final String CATEGORY = "Pago de deudas";
    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");

    private final JdbcClient jdbc;
    private final Spaces spaces;

    DebtService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    /** A quién se le debe (1–40), por ejemplo «Mamá». */
    public record Creditor(String value) {
        public Creditor {
            value = Text.optionalNote(value);
            if (value == null || value.length() > 40) {
                throw new IllegalArgumentException("persona inválida");
            }
        }
    }

    /** Por qué se debe (1–60), por ejemplo «Citas médicas». */
    public record Concept(String value) {
        public Concept {
            value = Text.optionalNote(value);
            if (value == null || value.length() > 60) {
                throw new IllegalArgumentException("concepto inválido");
            }
        }
    }

    /**
     * Datos de una deuda. {@code paidBefore}: lo que ya se había pagado antes de anotarla (no es
     * un gasto). {@code installment}/{@code installmentDay}: la cuota acordada, opcional.
     */
    public record Data(Creditor creditor, Concept concept, Money money, LocalDate borrowedOn, LocalDate dueDate,
                       BigDecimal paidBefore, BigDecimal installment, Integer installmentDay) {
        public Data {
            if (money.amount().signum() <= 0) {
                throw new IllegalArgumentException("el monto de la deuda va mayor a cero");
            }
            if (dueDate != null && dueDate.isBefore(borrowedOn)) {
                throw new IllegalArgumentException("la fecha acordada va después de la fecha de la deuda");
            }
            paidBefore = paidBefore == null ? BigDecimal.ZERO : new Money(paidBefore, money.currency()).amount();
            if (paidBefore.compareTo(money.amount()) >= 0) {
                throw new IllegalArgumentException("lo pagado antes va menor al monto");
            }
            if (installment != null) {
                installment = new Money(installment, money.currency()).amount();
                if (installment.signum() <= 0 || installment.compareTo(money.amount()) > 0) {
                    throw new IllegalArgumentException("cuota inválida");
                }
            }
            if (installmentDay != null && (installment == null || installmentDay < 1 || installmentDay > 31)) {
                throw new IllegalArgumentException("día de la cuota inválido");
            }
        }

        public Data(Creditor creditor, Concept concept, Money money, LocalDate borrowedOn, LocalDate dueDate) {
            this(creditor, concept, money, borrowedOn, dueDate, null, null, null);
        }
    }

    /**
     * Una deuda con todo lo que la cubre. {@code settledOn}: el día en que ya no faltó nada (el
     * último abono o el perdón). {@code paidThisMonth}: abonos del mes en curso (para la cuota);
     * {@code installmentDueDate}: el día de la cuota este mes, si tiene día.
     */
    public record DebtView(UUID id, String creditor, String concept, BigDecimal amount, Money.Currency currency,
                           LocalDate borrowedOn, LocalDate dueDate, BigDecimal paidBefore, BigDecimal forgiven,
                           LocalDate forgivenOn, BigDecimal paid, BigDecimal remaining, int payments,
                           LocalDate lastPaidOn, LocalDate settledOn, BigDecimal installment, Integer installmentDay,
                           LocalDate installmentDueDate, BigDecimal paidThisMonth, UUID createdBy) {}

    private static final String SELECT = """
        SELECT d.id, d.creditor, d.concept, d.amount, d.currency, d.borrowed_on, d.due_date, d.created_by,
               d.paid_before, d.forgiven, d.forgiven_on, d.installment, d.installment_day,
               CASE WHEN d.installment_day IS NULL THEN NULL
                    ELSE recurring_due_date(d.installment_day, ?::date) END AS installment_due,
               COALESCE(p.paid, 0)::numeric(14,2) AS paid, COALESCE(p.n, 0) AS payments, p.last_on,
               COALESCE(p.this_month, 0)::numeric(14,2) AS this_month
          FROM debt d
          LEFT JOIN LATERAL (
               SELECT sum(t.amount) AS paid, count(*) AS n, max(t.tx_date) AS last_on,
                      sum(t.amount) FILTER (WHERE date_trunc('month', t.tx_date) = date_trunc('month', ?::date)) AS this_month
                 FROM transaction t WHERE t.debt_id = d.id AND t.space_id = d.space_id) p ON true
        """;

    private static LocalDate today() {
        return LocalDate.now(COSTA_RICA);
    }

    @Transactional(readOnly = true)
    public List<DebtView> list(UUID spaceId) {
        spaces.requireMember(spaceId);
        LocalDate today = today();
        return jdbc.sql(SELECT + " WHERE d.space_id = ? ORDER BY lower(d.creditor), d.borrowed_on, d.created_at")
            .params(today, today, spaceId).query(DebtService::map).list();
    }

    @Transactional
    public DebtView create(UUID spaceId, Data d) {
        spaces.requireWritable(spaceId);
        UUID id = jdbc.sql("""
                INSERT INTO debt (space_id, created_by, creditor, concept, amount, currency, borrowed_on, due_date,
                                  paid_before, installment, installment_day)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), d.creditor().value(), d.concept().value(), d.money().amount(),
                d.money().currency().name(), d.borrowedOn(), d.dueDate(), d.paidBefore(), d.installment(),
                d.installmentDay())
            .query(UUID.class).single();
        return find(spaceId, id);
    }

    @Transactional
    public DebtView update(UUID spaceId, UUID id, Data d) {
        spaces.requireWritable(spaceId);
        DebtView current = lock(spaceId, id);
        if (current.paid().signum() > 0 && current.currency() != d.money().currency()) {
            throw new ConflictException("Esta deuda ya tiene abonos: no puede cambiar de moneda.");
        }
        BigDecimal covered = current.paid().add(current.forgiven()).add(d.paidBefore());
        if (d.money().amount().compareTo(covered) < 0) {
            throw new ConflictException("Ya está cubierto más que eso. El monto no puede ser menor a lo abonado,"
                + " lo pagado antes y lo perdonado.");
        }
        jdbc.sql("""
                UPDATE debt SET creditor = ?, concept = ?, amount = ?, currency = ?, borrowed_on = ?, due_date = ?,
                                paid_before = ?, installment = ?, installment_day = ?
                 WHERE id = ? AND space_id = ?""")
            .params(d.creditor().value(), d.concept().value(), d.money().amount(), d.money().currency().name(),
                d.borrowedOn(), d.dueDate(), d.paidBefore(), d.installment(), d.installmentDay(), id, spaceId)
            .update();
        return find(spaceId, id);
    }

    /** «Me lo perdonó»: lo que falta queda perdonado hoy, sin gasto. */
    @Transactional
    public DebtView forgive(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        DebtView current = lock(spaceId, id);
        if (current.remaining().signum() <= 0) {
            throw new ConflictException("Esta deuda ya no tiene nada por pagar.");
        }
        jdbc.sql("UPDATE debt SET forgiven = forgiven + ?, forgiven_on = ? WHERE id = ? AND space_id = ?")
            .params(current.remaining(), today(), id, spaceId).update();
        return find(spaceId, id);
    }

    /** Quitar el perdón: la deuda vuelve a tener lo que faltaba. */
    @Transactional
    public DebtView unforgive(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        lock(spaceId, id);
        jdbc.sql("UPDATE debt SET forgiven = 0, forgiven_on = NULL WHERE id = ? AND space_id = ?")
            .params(id, spaceId).update();
        return find(spaceId, id);
    }

    /** Borrar la deuda no borra los abonos: quedan como gastos (sí se pagaron). */
    @Transactional
    public void delete(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        if (jdbc.sql("DELETE FROM debt WHERE id = ? AND space_id = ?").params(id, spaceId).update() == 0) {
            throw new NotFoundException();
        }
    }

    /**
     * Lo usa el servicio de movimientos antes de anotar, editar o ligar un abono: la deuda es de
     * este espacio (si no, 404), va en la misma moneda y el abono no pasa lo que falta. Bloquea la
     * deuda hasta el final de la transacción (dos abonos a la vez no la pasan entre los dos).
     *
     * @param replacing el abono que se está editando o ligando (su monto no cuenta dos veces), o null.
     */
    public void requirePayable(UUID spaceId, UUID debtId, Money money, UUID replacing) {
        DebtView debt = lock(spaceId, debtId);
        if (debt.currency() != money.currency()) {
            throw new ConflictException("El abono va en la moneda de la deuda.");
        }
        BigDecimal paid = replacing == null ? debt.paid() : jdbc.sql("""
                SELECT COALESCE(sum(amount), 0) FROM transaction WHERE debt_id = ? AND space_id = ? AND id <> ?""")
            .params(debtId, spaceId, replacing).query(BigDecimal.class).single();
        BigDecimal free = debt.amount().subtract(debt.paidBefore()).subtract(debt.forgiven()).subtract(paid);
        if (money.amount().compareTo(free) > 0) {
            throw new ConflictException("Ese abono pasa lo que falta de la deuda.");
        }
    }

    private DebtView lock(UUID spaceId, UUID id) {
        jdbc.sql("SELECT 1 FROM debt WHERE id = ? AND space_id = ? FOR UPDATE").params(id, spaceId)
            .query(Integer.class).optional().orElseThrow(NotFoundException::new);
        return find(spaceId, id);
    }

    private DebtView find(UUID spaceId, UUID id) {
        LocalDate today = today();
        return jdbc.sql(SELECT + " WHERE d.id = ? AND d.space_id = ?").params(today, today, id, spaceId)
            .query(DebtService::map).optional().orElseThrow(NotFoundException::new);
    }

    private static DebtView map(ResultSet rs, int n) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal paid = rs.getBigDecimal("paid");
        BigDecimal paidBefore = rs.getBigDecimal("paid_before");
        BigDecimal forgiven = rs.getBigDecimal("forgiven");
        BigDecimal remaining = amount.subtract(paidBefore).subtract(forgiven).subtract(paid);
        LocalDate last = rs.getObject("last_on", LocalDate.class);
        LocalDate forgivenOn = rs.getObject("forgiven_on", LocalDate.class);
        LocalDate settledOn = null;
        if (remaining.signum() <= 0) {
            settledOn = last == null ? forgivenOn : forgivenOn == null || last.isAfter(forgivenOn) ? last : forgivenOn;
        }
        return new DebtView(rs.getObject("id", UUID.class), rs.getString("creditor"), rs.getString("concept"),
            amount, Money.Currency.valueOf(rs.getString("currency")), rs.getObject("borrowed_on", LocalDate.class),
            rs.getObject("due_date", LocalDate.class), paidBefore, forgiven, forgivenOn, paid, remaining,
            rs.getInt("payments"), last, settledOn, rs.getBigDecimal("installment"),
            rs.getObject("installment_day", Integer.class), rs.getObject("installment_due", LocalDate.class),
            rs.getBigDecimal("this_month"), rs.getObject("created_by", UUID.class));
    }
}

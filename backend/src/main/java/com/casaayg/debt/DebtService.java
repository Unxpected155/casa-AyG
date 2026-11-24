package com.casaayg.debt;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Deudas (V22): lo que se le debe a una persona. Lo que falta = monto − abonos; los abonos son
 * movimientos ligados (los anota TransactionService). La BD repite cada regla en triggers.
 */
@Service
public class DebtService {

    public static final String CATEGORY = "Pago de deudas";

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

    public record Data(Creditor creditor, Concept concept, Money money, LocalDate borrowedOn, LocalDate dueDate) {
        public Data {
            if (money.amount().signum() <= 0) {
                throw new IllegalArgumentException("el monto de la deuda va mayor a cero");
            }
            if (dueDate != null && dueDate.isBefore(borrowedOn)) {
                throw new IllegalArgumentException("la fecha acordada va después de la fecha de la deuda");
            }
        }
    }

    /**
     * Una deuda con lo abonado. {@code settledOn}: el día del último abono, si ya no falta nada.
     */
    public record DebtView(UUID id, String creditor, String concept, BigDecimal amount, Money.Currency currency,
                           LocalDate borrowedOn, LocalDate dueDate, BigDecimal paid, BigDecimal remaining,
                           int payments, LocalDate lastPaidOn, LocalDate settledOn, UUID createdBy) {}

    private static final String SELECT = """
        SELECT d.id, d.creditor, d.concept, d.amount, d.currency, d.borrowed_on, d.due_date, d.created_by,
               COALESCE(p.paid, 0) AS paid, COALESCE(p.n, 0) AS payments, p.last_on
          FROM debt d
          LEFT JOIN LATERAL (
               SELECT sum(t.amount) AS paid, count(*) AS n, max(t.tx_date) AS last_on
                 FROM transaction t WHERE t.debt_id = d.id AND t.space_id = d.space_id) p ON true
        """;

    @Transactional(readOnly = true)
    public List<DebtView> list(UUID spaceId) {
        spaces.requireMember(spaceId);
        return jdbc.sql(SELECT + " WHERE d.space_id = ? ORDER BY lower(d.creditor), d.borrowed_on, d.created_at")
            .param(spaceId).query(DebtService::map).list();
    }

    @Transactional
    public DebtView create(UUID spaceId, Data d) {
        spaces.requireWritable(spaceId);
        UUID id = jdbc.sql("""
                INSERT INTO debt (space_id, created_by, creditor, concept, amount, currency, borrowed_on, due_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), d.creditor().value(), d.concept().value(), d.money().amount(),
                d.money().currency().name(), d.borrowedOn(), d.dueDate())
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
        if (d.money().amount().compareTo(current.paid()) < 0) {
            throw new ConflictException("Ya abonaste más que eso. El monto no puede ser menor a lo abonado.");
        }
        jdbc.sql("""
                UPDATE debt SET creditor = ?, concept = ?, amount = ?, currency = ?, borrowed_on = ?, due_date = ?
                 WHERE id = ? AND space_id = ?""")
            .params(d.creditor().value(), d.concept().value(), d.money().amount(), d.money().currency().name(),
                d.borrowedOn(), d.dueDate(), id, spaceId)
            .update();
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
     * Lo usa el servicio de movimientos antes de anotar o editar un abono: la deuda es de este
     * espacio (si no, 404), va en la misma moneda y el abono no pasa lo que falta. Bloquea la
     * deuda hasta el final de la transacción (dos abonos a la vez no la pasan entre los dos).
     *
     * @param replacing el abono que se está editando (su monto viejo no cuenta), o null.
     */
    public void requirePayable(UUID spaceId, UUID debtId, Money money, UUID replacing) {
        DebtView debt = lock(spaceId, debtId);
        if (debt.currency() != money.currency()) {
            throw new ConflictException("El abono va en la moneda de la deuda.");
        }
        BigDecimal paid = replacing == null ? debt.paid() : jdbc.sql("""
                SELECT COALESCE(sum(amount), 0) FROM transaction WHERE debt_id = ? AND space_id = ? AND id <> ?""")
            .params(debtId, spaceId, replacing).query(BigDecimal.class).single();
        if (paid.add(money.amount()).compareTo(debt.amount()) > 0) {
            throw new ConflictException("Ese abono pasa lo que falta de la deuda.");
        }
    }

    private DebtView lock(UUID spaceId, UUID id) {
        jdbc.sql("SELECT 1 FROM debt WHERE id = ? AND space_id = ? FOR UPDATE").params(id, spaceId)
            .query(Integer.class).optional().orElseThrow(NotFoundException::new);
        return find(spaceId, id);
    }

    private DebtView find(UUID spaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = ? AND d.space_id = ?").params(id, spaceId)
            .query(DebtService::map).optional().orElseThrow(NotFoundException::new);
    }

    private static DebtView map(ResultSet rs, int n) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal paid = rs.getBigDecimal("paid");
        BigDecimal remaining = amount.subtract(paid);
        LocalDate last = rs.getObject("last_on", LocalDate.class);
        return new DebtView(rs.getObject("id", UUID.class), rs.getString("creditor"), rs.getString("concept"),
            amount, Money.Currency.valueOf(rs.getString("currency")), rs.getObject("borrowed_on", LocalDate.class),
            rs.getObject("due_date", LocalDate.class), paid, remaining, rs.getInt("payments"), last,
            remaining.signum() == 0 ? last : null, rs.getObject("created_by", UUID.class));
    }
}

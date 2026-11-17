package com.casaayg.recurring;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.domain.Text;
import com.casaayg.place.PlaceService;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

/** Pagos fijos (V9): qué hay que pagar cada mes y si ya se pagó. */
@Service
public class RecurringService {

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final PlaceService places;

    RecurringService(JdbcClient jdbc, Spaces spaces, PlaceService places) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.places = places;
    }

    /** Nombre de un pago fijo (1–60). */
    public record Name(String value) {
        public Name {
            value = Text.optionalNote(value);
            if (value == null || value.length() > 60) {
                throw new IllegalArgumentException("nombre inválido");
            }
        }
    }

    public record Data(Name name, UUID categoryId, UUID placeId, Integer dueDay, Money expected,
                       Money.Currency currency, int notifyDaysBefore, boolean active) {}

    /**
     * Un pago fijo visto en un mes: su fecha ese mes (si tiene día) y, si ya se pagó, con qué
     * movimiento.
     */
    public record RecurringView(UUID id, String name, UUID categoryId, UUID placeId, Integer dueDay,
                                BigDecimal expectedAmount, Money.Currency currency, int notifyDaysBefore,
                                boolean active, LocalDate dueDate, UUID paidTransactionId, BigDecimal paidAmount,
                                LocalDate paidOn) {}

    @Transactional(readOnly = true)
    public List<RecurringView> list(UUID spaceId, YearMonth month) {
        spaces.requireMember(spaceId);
        LocalDate first = month.atDay(1);
        return jdbc.sql("""
                SELECT r.id, r.name, r.category_id, r.place_id, r.due_day, r.expected_amount, r.currency,
                       r.notify_days_before, r.active,
                       CASE WHEN r.due_day IS NULL THEN NULL ELSE recurring_due_date(r.due_day, ?::date) END AS due_date,
                       t.id AS paid_id, t.amount AS paid_amount, t.tx_date AS paid_on
                  FROM recurring_payment r
                  LEFT JOIN LATERAL (
                       SELECT t.id, t.amount, t.tx_date FROM transaction t
                        WHERE t.recurring_id = r.id AND t.space_id = r.space_id
                          AND t.tx_date >= ? AND t.tx_date < ?
                        ORDER BY t.tx_date DESC LIMIT 1) t ON true
                 WHERE r.space_id = ?
                 ORDER BY r.active DESC, r.due_day NULLS LAST, lower(r.name)""")
            .params(first, first, month.plusMonths(1).atDay(1), spaceId)
            .query(RecurringService::map).list();
    }

    @Transactional
    public RecurringView create(UUID spaceId, Data d) {
        spaces.requireWritable(spaceId);
        validate(spaceId, d);
        UUID id = jdbc.sql("""
                INSERT INTO recurring_payment (space_id, created_by, name, category_id, place_id, due_day,
                                               expected_amount, currency, notify_days_before, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), d.name().value(), d.categoryId(), d.placeId(), d.dueDay(),
                d.expected() == null ? null : d.expected().amount(), d.currency().name(), d.notifyDaysBefore(),
                d.active())
            .query(UUID.class).single();
        return find(spaceId, id);
    }

    @Transactional
    public RecurringView update(UUID spaceId, UUID id, Data d) {
        spaces.requireWritable(spaceId);
        validate(spaceId, d);
        int rows = jdbc.sql("""
                UPDATE recurring_payment SET name = ?, category_id = ?, place_id = ?, due_day = ?,
                       expected_amount = ?, currency = ?, notify_days_before = ?, active = ?
                 WHERE id = ? AND space_id = ?""")
            .params(d.name().value(), d.categoryId(), d.placeId(), d.dueDay(),
                d.expected() == null ? null : d.expected().amount(), d.currency().name(), d.notifyDaysBefore(),
                d.active(), id, spaceId)
            .update();
        if (rows == 0) {
            throw new NotFoundException();
        }
        return find(spaceId, id);
    }

    @Transactional
    public void delete(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        if (jdbc.sql("DELETE FROM recurring_payment WHERE id = ? AND space_id = ?").params(id, spaceId).update() == 0) {
            throw new NotFoundException();
        }
    }

    /** Lo usa el servicio de movimientos: el pago fijo es de este espacio (si no, 404). */
    public void requireInSpace(UUID spaceId, UUID recurringId) {
        jdbc.sql("SELECT 1 FROM recurring_payment WHERE id = ? AND space_id = ?")
            .params(recurringId, spaceId).query(Integer.class).optional()
            .orElseThrow(NotFoundException::new);
    }

    private void validate(UUID spaceId, Data d) {
        String group = jdbc.sql("SELECT category_group FROM category WHERE id = ? AND space_id = ?")
            .params(d.categoryId(), spaceId).query(String.class).optional()
            .orElseThrow(NotFoundException::new);
        if ("INGRESO".equals(group)) {
            throw new IllegalArgumentException("un pago fijo es un gasto");
        }
        if (d.placeId() != null && places.requirePlace(spaceId, d.placeId(), false) != d.currency()) {
            throw new ConflictException("La moneda del pago no coincide con la de la cuenta.");
        }
        if (d.expected() != null && d.expected().currency() != d.currency()) {
            throw new IllegalArgumentException("moneda");
        }
    }

    private RecurringView find(UUID spaceId, UUID id) {
        YearMonth now = YearMonth.now(java.time.ZoneId.of("America/Costa_Rica"));
        return list(spaceId, now).stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(NotFoundException::new);
    }

    private static RecurringView map(ResultSet rs, int n) throws SQLException {
        return new RecurringView(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getObject("category_id", UUID.class), rs.getObject("place_id", UUID.class),
            rs.getObject("due_day", Integer.class), rs.getBigDecimal("expected_amount"),
            Money.Currency.valueOf(rs.getString("currency")), rs.getInt("notify_days_before"),
            rs.getBoolean("active"), rs.getObject("due_date", LocalDate.class),
            rs.getObject("paid_id", UUID.class), rs.getBigDecimal("paid_amount"),
            rs.getObject("paid_on", LocalDate.class));
    }
}

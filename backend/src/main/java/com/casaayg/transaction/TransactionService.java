package com.casaayg.transaction;

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
import com.casaayg.domain.Text.Description;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

@Service
public class TransactionService {

    public static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Spaces spaces;

    TransactionService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    /** Datos validados de un movimiento (los arma el controlador con domain primitives). */
    public record TransactionData(UUID categoryId, LocalDate date, Description description, Money money,
                                  String paymentMethod) {}

    public record TransactionView(UUID id, UUID categoryId, LocalDate date, String description,
                                  java.math.BigDecimal amount, Money.Currency currency, String paymentMethod,
                                  String source, UUID createdBy, boolean beforeMerge) {}

    public record Page(List<TransactionView> items, int page, int size, long total) {}

    private static final String COLUMNS = """
        id, category_id, tx_date, description, amount, currency, payment_method, source,
        created_by, origin_space_id IS NOT NULL AS before_merge""";

    @Transactional(readOnly = true)
    public Page list(UUID spaceId, YearMonth month, int page, int size) {
        spaces.requireMember(spaceId);
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        long total = jdbc.sql("SELECT count(*) FROM transaction WHERE space_id = ? AND tx_date >= ? AND tx_date < ?")
            .params(spaceId, from, to).query(Long.class).single();
        List<TransactionView> items = jdbc.sql("SELECT " + COLUMNS
                + " FROM transaction WHERE space_id = ? AND tx_date >= ? AND tx_date < ?"
                + " ORDER BY tx_date DESC, created_at DESC LIMIT ? OFFSET ?")
            .params(spaceId, from, to, size, (long) page * size)
            .query(TransactionService::map).list();
        return new Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public TransactionView get(UUID spaceId, UUID id) {
        spaces.requireMember(spaceId);
        return find(spaceId, id);
    }

    @Transactional
    public TransactionView create(UUID spaceId, TransactionData data) {
        spaces.requireWritable(spaceId);
        requireCategory(spaceId, data.categoryId());
        UUID id = jdbc.sql("""
                INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount,
                                         currency, payment_method, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL') RETURNING id""")
            .params(spaceId, CurrentUser.require(), data.categoryId(), data.date(), data.description().value(),
                data.money().amount(), data.money().currency().name(), data.paymentMethod())
            .query(UUID.class).single();
        return find(spaceId, id);
    }

    @Transactional
    public TransactionView update(UUID spaceId, UUID id, TransactionData data) {
        spaces.requireWritable(spaceId);
        requireCategory(spaceId, data.categoryId());
        int rows = jdbc.sql("""
                UPDATE transaction SET category_id = ?, tx_date = ?, description = ?, amount = ?, currency = ?,
                                       payment_method = ?, updated_at = now()
                 WHERE id = ? AND space_id = ?""")
            .params(data.categoryId(), data.date(), data.description().value(), data.money().amount(),
                data.money().currency().name(), data.paymentMethod(), id, spaceId)
            .update();
        if (rows == 0) {
            throw new NotFoundException();
        }
        return find(spaceId, id);
    }

    @Transactional
    public void delete(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        if (jdbc.sql("DELETE FROM transaction WHERE id = ? AND space_id = ?").params(id, spaceId).update() == 0) {
            throw new NotFoundException();
        }
    }

    private TransactionView find(UUID spaceId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transaction WHERE id = ? AND space_id = ?")
            .params(id, spaceId).query(TransactionService::map).optional()
            .orElseThrow(NotFoundException::new);
    }

    /** Usar una categoría de otro espacio responde 404, igual que una inexistente. */
    private void requireCategory(UUID spaceId, UUID categoryId) {
        jdbc.sql("SELECT 1 FROM category WHERE id = ? AND space_id = ?")
            .params(categoryId, spaceId).query(Integer.class).optional()
            .orElseThrow(NotFoundException::new);
    }

    private static TransactionView map(ResultSet rs, int n) throws SQLException {
        return new TransactionView(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
            rs.getObject("tx_date", LocalDate.class), rs.getString("description"), rs.getBigDecimal("amount"),
            Money.Currency.valueOf(rs.getString("currency")), rs.getString("payment_method"),
            rs.getString("source"), rs.getObject("created_by", UUID.class), rs.getBoolean("before_merge"));
    }
}

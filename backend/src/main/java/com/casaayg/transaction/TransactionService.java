package com.casaayg.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.casaayg.place.PlaceService;
import com.casaayg.recurring.RecurringService;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

@Service
public class TransactionService {

    public static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final PlaceService places;
    private final RecurringService recurring;

    TransactionService(JdbcClient jdbc, Spaces spaces, PlaceService places, RecurringService recurring) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.places = places;
        this.recurring = recurring;
    }

    /** El diezmo es el 10 % del monto bruto del ingreso. */
    public static final BigDecimal TITHE_RATE = new BigDecimal("0.10");
    public static final String TITHE_CATEGORY = "Diezmo";

    /**
     * Datos validados de un movimiento (los arma el controlador con domain primitives).
     * {@code gross}: monto bruto de un ingreso (base del diezmo); null = el mismo monto.
     * {@code tithe}: anotar también el diezmo de este ingreso.
     * {@code recurringId}: el pago fijo que se está pagando (solo al anotar).
     */
    public record TransactionData(UUID categoryId, LocalDate date, Description description, Money money,
                                  String paymentMethod, UUID placeId, BigDecimal gross, boolean tithe,
                                  UUID recurringId) {

        public TransactionData(UUID categoryId, LocalDate date, Description description, Money money,
                               String paymentMethod, UUID placeId) {
            this(categoryId, date, description, money, paymentMethod, placeId, null, false, null);
        }

        public TransactionData(UUID categoryId, LocalDate date, Description description, Money money,
                               String paymentMethod, UUID placeId, BigDecimal gross, boolean tithe) {
            this(categoryId, date, description, money, paymentMethod, placeId, gross, tithe, null);
        }

        /** Base del diezmo: el bruto si se dio, si no el monto. */
        BigDecimal titheBase() {
            return gross != null ? gross : money.amount();
        }
    }

    public record TransactionView(UUID id, UUID categoryId, LocalDate date, String description,
                                  BigDecimal amount, Money.Currency currency, String paymentMethod,
                                  String source, UUID createdBy, boolean beforeMerge, UUID placeId,
                                  BigDecimal grossAmount, UUID titheOf, UUID titheId, UUID recurringId) {}

    /**
     * Lo que se sugiere al anotar: la categoría que se usó antes con esa descripción, el último
     * lugar y, si se pidió una categoría, el último bruto anotado en ella (el salario bruto).
     */
    public record Suggestion(UUID categoryId, UUID placeId, BigDecimal lastGross) {}

    /** Sumas de lo filtrado, por moneda: responden "¿cuánto gastamos en Uber este mes?". */
    public record Totals(BigDecimal incomeCrc, BigDecimal expenseCrc, BigDecimal incomeUsd, BigDecimal expenseUsd) {}

    public record Page(List<TransactionView> items, int page, int size, long total, Totals totals) {}

    /**
     * Filtros opcionales. {@code text}: parte de la descripción (sin importar mayúsculas; los
     * comodines de LIKE se escapan). {@code allMonths}: buscar en toda la historia, no solo el mes.
     */
    public record Filter(String text, UUID categoryId, UUID placeId, boolean allMonths) {
        public static final Filter NONE = new Filter(null, null, null, false);
    }

    /** Letras con tilde del español (y vecinas) y su versión sin tilde, para buscar sin tildes. */
    private static final String ACCENTED = "áàäâéèëêíìïîóòöôúùüûñç";
    private static final String PLAIN = "aaaaeeeeiiiioooouuuunc";

    private static final String COLUMNS = """
        id, category_id, tx_date, description, amount, currency, payment_method, source,
        created_by, origin_space_id IS NOT NULL AS before_merge, place_id, gross_amount, tithe_of,
        (SELECT d.id FROM transaction d WHERE d.tithe_of = transaction.id) AS tithe_id, recurring_id""";

    @Transactional(readOnly = true)
    public Page list(UUID spaceId, YearMonth month, int page, int size) {
        return list(spaceId, month, page, size, Filter.NONE);
    }

    @Transactional(readOnly = true)
    public Page list(UUID spaceId, YearMonth month, int page, int size, Filter f) {
        spaces.requireMember(spaceId);
        // El WHERE se arma solo con fragmentos fijos; los valores van SIEMPRE como parámetros.
        StringBuilder where = new StringBuilder(" WHERE space_id = ?");
        List<Object> params = new java.util.ArrayList<>(List.of(spaceId));
        if (!f.allMonths()) {
            where.append(" AND tx_date >= ? AND tx_date < ?");
            params.add(month.atDay(1));
            params.add(month.plusMonths(1).atDay(1));
        }
        String text = f.text() == null ? "" : f.text().strip().toLowerCase(java.util.Locale.ROOT);
        if (!text.isEmpty()) {
            // Sin tildes en los dos lados: "super" encuentra "Súper" y "cafe" encuentra "Café".
            where.append(" AND translate(lower(description), '" + ACCENTED + "', '" + PLAIN + "') LIKE ?");
            String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            params.add("%" + plain.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.categoryId() != null) {
            where.append(" AND category_id = ?");
            params.add(f.categoryId());
        }
        if (f.placeId() != null) {
            where.append(" AND place_id = ?");
            params.add(f.placeId());
        }
        Object[] filter = params.toArray();
        record Agg(long count, BigDecimal incomeCrc, BigDecimal expenseCrc, BigDecimal incomeUsd, BigDecimal expenseUsd) {}
        Agg agg = jdbc.sql("""
                SELECT count(*),
                       COALESCE(sum(amount) FILTER (WHERE currency = 'CRC' AND income), 0),
                       COALESCE(sum(amount) FILTER (WHERE currency = 'CRC' AND NOT income), 0),
                       COALESCE(sum(amount) FILTER (WHERE currency = 'USD' AND income), 0),
                       COALESCE(sum(amount) FILTER (WHERE currency = 'USD' AND NOT income), 0)
                  FROM (SELECT t.amount, t.currency, c.category_group = 'INGRESO' AS income
                          FROM transaction t JOIN category c ON c.id = t.category_id""" + where.toString().replace(" space_id", " t.space_id")
                    + ") x")
            .params(filter)
            .query((rs, n) -> new Agg(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5)))
            .single();
        params.add(size);
        params.add((long) page * size);
        List<TransactionView> items = jdbc.sql("SELECT " + COLUMNS + " FROM transaction" + where
                + " ORDER BY tx_date DESC, created_at DESC LIMIT ? OFFSET ?")
            .params(params.toArray())
            .query(TransactionService::map).list();
        return new Page(items, page, size, agg.count(),
            new Totals(agg.incomeCrc(), agg.expenseCrc(), agg.incomeUsd(), agg.expenseUsd()));
    }

    @Transactional(readOnly = true)
    public TransactionView get(UUID spaceId, UUID id) {
        spaces.requireMember(spaceId);
        return find(spaceId, id);
    }

    @Transactional
    public TransactionView create(UUID spaceId, TransactionData data) {
        spaces.requireWritable(spaceId);
        boolean income = requireCategory(spaceId, data.categoryId());
        requirePlace(spaceId, data);
        requireTitheOnlyForIncome(income, data);
        if (data.recurringId() != null) {
            recurring.requireInSpace(spaceId, data.recurringId());
        }
        UUID id = jdbc.sql("""
                INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount,
                                         currency, payment_method, source, place_id, gross_amount, recurring_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), data.categoryId(), data.date(), data.description().value(),
                data.money().amount(), data.money().currency().name(), data.paymentMethod(), data.placeId(),
                income ? data.gross() : null, data.recurringId())
            .query(UUID.class).single();
        if (data.tithe()) {
            insertTithe(spaceId, id, data);
        }
        return find(spaceId, id);
    }

    @Transactional
    public TransactionView update(UUID spaceId, UUID id, TransactionData data) {
        spaces.requireWritable(spaceId);
        boolean income = requireCategory(spaceId, data.categoryId());
        requirePlace(spaceId, data);
        requireTitheOnlyForIncome(income, data);
        int rows = jdbc.sql("""
                UPDATE transaction SET category_id = ?, tx_date = ?, description = ?, amount = ?, currency = ?,
                                       payment_method = ?, place_id = ?, gross_amount = ?, updated_at = now()
                 WHERE id = ? AND space_id = ?""")
            .params(data.categoryId(), data.date(), data.description().value(), data.money().amount(),
                data.money().currency().name(), data.paymentMethod(), data.placeId(), income ? data.gross() : null,
                id, spaceId)
            .update();
        if (rows == 0) {
            throw new NotFoundException();
        }
        // El diezmo sigue a su ingreso: se ajusta, se agrega o se quita según la casilla.
        UUID tithe = find(spaceId, id).titheId();
        if (data.tithe() && tithe == null) {
            insertTithe(spaceId, id, data);
        } else if (data.tithe()) {
            jdbc.sql("""
                    UPDATE transaction SET tx_date = ?, amount = ?, currency = ?, place_id = ?, updated_at = now()
                     WHERE id = ? AND space_id = ?""")
                .params(data.date(), titheAmount(data), data.money().currency().name(), data.placeId(), tithe, spaceId)
                .update();
        } else if (tithe != null) {
            jdbc.sql("DELETE FROM transaction WHERE id = ? AND space_id = ?").params(tithe, spaceId).update();
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

    /**
     * Sugerencia para anotar rápido, calculada solo con los datos del propio espacio (nada sale
     * del servidor): la categoría más usada con esa misma descripción (o una que empiece igual)
     * y el último lugar que usó esta persona.
     */
    @Transactional(readOnly = true)
    public Suggestion suggest(UUID spaceId, String description, UUID categoryId) {
        spaces.requireMember(spaceId);
        UUID category = null;
        String text = description == null ? "" : description.strip().toLowerCase(java.util.Locale.ROOT);
        if (text.length() >= 2) {
            String prefix = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            category = jdbc.sql("""
                    SELECT t.category_id FROM transaction t JOIN category c ON c.id = t.category_id
                     WHERE t.space_id = ? AND c.active AND lower(t.description) LIKE ?
                     GROUP BY t.category_id
                     ORDER BY bool_or(lower(t.description) = ?) DESC, count(*) DESC, max(t.created_at) DESC
                     LIMIT 1""")
                .params(spaceId, prefix, text).query(UUID.class).optional().orElse(null);
        }
        UUID place = jdbc.sql("""
                SELECT t.place_id FROM transaction t JOIN place p ON p.id = t.place_id
                 WHERE t.space_id = ? AND t.created_by = current_app_user() AND NOT p.archived
                 ORDER BY t.created_at DESC LIMIT 1""")
            .param(spaceId).query(UUID.class).optional().orElse(null);
        BigDecimal lastGross = categoryId == null ? null : jdbc.sql("""
                SELECT gross_amount FROM transaction
                 WHERE space_id = ? AND category_id = ? AND created_by = current_app_user() AND gross_amount IS NOT NULL
                 ORDER BY tx_date DESC, created_at DESC LIMIT 1""")
            .params(spaceId, categoryId).query(BigDecimal.class).optional().orElse(null);
        return new Suggestion(category, place, lastGross);
    }

    /** El lugar es del espacio y de la misma moneda que el movimiento (la BD lo vuelve a revisar). */
    private void requirePlace(UUID spaceId, TransactionData data) {
        if (data.placeId() != null && places.requirePlace(spaceId, data.placeId(), true) != data.money().currency()) {
            throw new ConflictException("La moneda no coincide con la del lugar.");
        }
    }

    /**
     * Usar una categoría de otro espacio responde 404, igual que una inexistente.
     * @return si es una categoría de ingreso.
     */
    private boolean requireCategory(UUID spaceId, UUID categoryId) {
        return jdbc.sql("SELECT category_group = 'INGRESO' FROM category WHERE id = ? AND space_id = ?")
            .params(categoryId, spaceId).query(Boolean.class).optional()
            .orElseThrow(NotFoundException::new);
    }

    private static void requireTitheOnlyForIncome(boolean income, TransactionData data) {
        if (!income && (data.tithe() || data.gross() != null)) {
            throw new IllegalArgumentException("el diezmo y el bruto son solo para ingresos");
        }
    }

    static BigDecimal titheAmount(TransactionData data) {
        return data.titheBase().multiply(TITHE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    /** El gasto del diezmo: mismo día, lugar y moneda que su ingreso, en la categoría Diezmo. */
    private void insertTithe(UUID spaceId, UUID incomeId, TransactionData data) {
        UUID category = ensureCategory(spaceId, TITHE_CATEGORY, "NECESIDAD");
        String text = "Diezmo: " + data.description().value();
        jdbc.sql("""
                INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount,
                                         currency, source, place_id, tithe_of)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?)""")
            .params(spaceId, CurrentUser.require(), category, data.date(),
                text.length() > 200 ? text.substring(0, 200) : text,
                titheAmount(data), data.money().currency().name(), data.placeId(), incomeId)
            .update();
    }

    /** La categoría con ese nombre (sin importar mayúsculas), o una nueva si alguien la borró. */
    public UUID ensureCategory(UUID spaceId, String name, String group) {
        return jdbc.sql("SELECT id FROM category WHERE space_id = ? AND lower(name) = lower(?)")
            .params(spaceId, name).query(UUID.class).optional()
            .orElseGet(() -> jdbc.sql("""
                    INSERT INTO category (space_id, created_by, name, category_group)
                    VALUES (?, ?, ?, ?) RETURNING id""")
                .params(spaceId, CurrentUser.require(), name, group).query(UUID.class).single());
    }

    private static TransactionView map(ResultSet rs, int n) throws SQLException {
        return new TransactionView(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
            rs.getObject("tx_date", LocalDate.class), rs.getString("description"), rs.getBigDecimal("amount"),
            Money.Currency.valueOf(rs.getString("currency")), rs.getString("payment_method"),
            rs.getString("source"), rs.getObject("created_by", UUID.class), rs.getBoolean("before_merge"),
            rs.getObject("place_id", UUID.class), rs.getBigDecimal("gross_amount"), rs.getObject("tithe_of", UUID.class),
            rs.getObject("tithe_id", UUID.class), rs.getObject("recurring_id", UUID.class));
    }
}

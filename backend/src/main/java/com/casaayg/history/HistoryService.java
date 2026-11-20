package com.casaayg.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.space.Spaces;

/**
 * Historia: cómo se compara un mes con el promedio de los 3 anteriores, y los últimos 6 meses.
 * Todo en colones (los dólares se convierten con el tipo de cambio del día de cada movimiento).
 * El dinero movido entre lugares no cuenta: no es gasto ni ingreso.
 */
@Service
public class HistoryService {

    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");
    static final int WINDOW = 6;
    static final int BASELINE = 3;

    private final JdbcClient jdbc;
    private final Spaces spaces;

    HistoryService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    public record MonthTotals(YearMonth month, BigDecimal income, BigDecimal expenses, BigDecimal saved,
                              Integer savingsRate) {}

    /**
     * Una categoría en el mes elegido. {@code average}: su promedio mensual de los 3 meses anteriores.
     * {@code expected}: con qué se compara. Mes terminado: el promedio. Mes en curso: lo que en
     * promedio se llevaba gastado AL MISMO DÍA de esos meses (no un prorrateo: el alquiler se paga
     * completo el día 2 y eso no es "gastar de más"). {@code series}: los 6 meses.
     */
    public record CategoryTrend(UUID categoryId, String name, String group, BigDecimal current, BigDecimal average,
                                BigDecimal expected, BigDecimal delta, Integer deltaPct, List<BigDecimal> series) {}

    /**
     * @param partial         si el mes elegido todavía va en curso
     * @param elapsed         fracción del mes que ya pasó (1 si terminó)
     * @param baselineMonths  cuántos de los 3 meses anteriores tienen datos (0 = aún no hay con qué comparar)
     */
    public record History(YearMonth month, boolean partial, double elapsed, int baselineMonths,
                          List<MonthTotals> months, List<CategoryTrend> categories) {}

    /** {@code toDay}: lo de ese mes hasta el mismo día del mes que se está viendo. */
    record Cell(YearMonth month, UUID categoryId, String name, String group, BigDecimal amount, BigDecimal toDay) {}

    @Transactional(readOnly = true)
    public History history(UUID spaceId, YearMonth month) {
        spaces.requireMember(spaceId);
        YearMonth first = month.minusMonths(WINDOW - 1);
        LocalDate today = LocalDate.now(COSTA_RICA);
        int cutoffDay = YearMonth.from(today).equals(month) ? today.getDayOfMonth() : 31;
        List<Cell> cells = jdbc.sql("""
                WITH crc AS (
                    SELECT date_trunc('month', t.tx_date)::date AS m, c.id, c.name, c.category_group,
                           extract(day FROM t.tx_date) AS d,
                           CASE WHEN t.currency = 'CRC' THEN t.amount
                                ELSE COALESCE(t.amount * usd_sell_rate(t.tx_date), 0) END AS amount
                      FROM transaction t JOIN category c ON c.id = t.category_id
                     WHERE t.space_id = ? AND t.tx_date >= ? AND t.tx_date < ?)
                SELECT m, id, name, category_group, sum(amount) AS amount,
                       COALESCE(sum(amount) FILTER (WHERE d <= ?), 0) AS to_day
                  FROM crc GROUP BY 1, 2, 3, 4""")
            .params(spaceId, first.atDay(1), month.plusMonths(1).atDay(1), cutoffDay)
            .query((rs, n) -> new Cell(YearMonth.from(rs.getObject("m", LocalDate.class)), rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("category_group"), rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP),
                rs.getBigDecimal("to_day").setScale(2, RoundingMode.HALF_UP)))
            .list();
        History history = build(month, today, cells);
        // La casa no existía antes de casarse: esos meses no son "₡0", simplemente no van.
        YearMonth since = jdbc.sql("""
                SELECT created_at FROM space WHERE id = ? AND kind = 'FAMILIA'""")
            .param(spaceId).query(java.time.OffsetDateTime.class).optional()
            .map(t -> YearMonth.from(t.atZoneSameInstant(COSTA_RICA))).orElse(null);
        if (since == null || !since.isAfter(first)) {
            return history;
        }
        List<MonthTotals> kept = history.months().stream().filter(m -> !m.month().isBefore(since)).toList();
        return new History(history.month(), history.partial(), history.elapsed(), history.baselineMonths(),
            kept.isEmpty() ? history.months().subList(history.months().size() - 1, history.months().size()) : kept,
            history.categories());
    }

    /** El cálculo, separado de la BD para probarlo con datos fijos. */
    static History build(YearMonth month, LocalDate today, List<Cell> cells) {
        YearMonth first = month.minusMonths(WINDOW - 1);
        boolean partial = YearMonth.from(today).equals(month);
        double elapsed = partial ? (double) today.getDayOfMonth() / month.lengthOfMonth() : 1.0;

        List<MonthTotals> months = new ArrayList<>();
        Map<YearMonth, Boolean> hasData = new HashMap<>();
        for (int i = 0; i < WINDOW; i++) {
            YearMonth m = first.plusMonths(i);
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expenses = BigDecimal.ZERO;
            for (Cell c : cells) {
                if (c.month().equals(m)) {
                    hasData.put(m, true);
                    if ("INGRESO".equals(c.group())) {
                        income = income.add(c.amount());
                    } else {
                        expenses = expenses.add(c.amount());
                    }
                }
            }
            BigDecimal saved = income.subtract(expenses);
            Integer rate = income.signum() > 0
                ? saved.multiply(BigDecimal.valueOf(100)).divide(income, 0, RoundingMode.HALF_UP).intValue() : null;
            months.add(new MonthTotals(m, income, expenses, saved, rate));
        }

        // Base de comparación: los 3 meses anteriores QUE TIENEN datos (si recién empiezan, no se
        // cuentan como ceros los meses en que todavía no usaban la app).
        List<YearMonth> baseline = new ArrayList<>();
        for (int i = 1; i <= BASELINE; i++) {
            if (hasData.getOrDefault(month.minusMonths(i), false)) {
                baseline.add(month.minusMonths(i));
            }
        }

        Map<UUID, Cell> meta = new LinkedHashMap<>();
        Map<UUID, Map<YearMonth, BigDecimal>> byCategory = new HashMap<>();
        Map<UUID, Map<YearMonth, BigDecimal>> toDayByCategory = new HashMap<>();
        for (Cell c : cells) {
            meta.putIfAbsent(c.categoryId(), c);
            byCategory.computeIfAbsent(c.categoryId(), k -> new HashMap<>()).merge(c.month(), c.amount(), BigDecimal::add);
            toDayByCategory.computeIfAbsent(c.categoryId(), k -> new HashMap<>()).merge(c.month(), c.toDay(), BigDecimal::add);
        }
        List<CategoryTrend> categories = new ArrayList<>();
        for (Map.Entry<UUID, Cell> e : meta.entrySet()) {
            Cell c = e.getValue();
            Map<YearMonth, BigDecimal> values = byCategory.get(e.getKey());
            Map<YearMonth, BigDecimal> toDay = toDayByCategory.get(e.getKey());
            List<BigDecimal> series = new ArrayList<>();
            for (int i = 0; i < WINDOW; i++) {
                series.add(values.getOrDefault(first.plusMonths(i), BigDecimal.ZERO));
            }
            BigDecimal current = values.getOrDefault(month, BigDecimal.ZERO);
            BigDecimal average = null;
            BigDecimal expected = null;
            BigDecimal delta = null;
            Integer deltaPct = null;
            if (!baseline.isEmpty()) {
                average = baseline.stream().map(m -> values.getOrDefault(m, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(baseline.size()), 2, RoundingMode.HALF_UP);
                expected = !partial ? average
                    : baseline.stream().map(m -> toDay.getOrDefault(m, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(baseline.size()), 2, RoundingMode.HALF_UP);
                delta = current.subtract(expected);
                deltaPct = expected.signum() > 0
                    ? delta.multiply(BigDecimal.valueOf(100)).divide(expected, 0, RoundingMode.HALF_UP).intValue() : null;
            }
            categories.add(new CategoryTrend(c.categoryId(), c.name(), c.group(), current, average, expected, delta,
                deltaPct, series));
        }
        categories.sort((a, b) -> b.current().compareTo(a.current()));
        return new History(month, partial, elapsed, baseline.size(), months, categories);
    }
}

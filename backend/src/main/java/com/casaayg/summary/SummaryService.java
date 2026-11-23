package com.casaayg.summary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.CategoryGroup;
import com.casaayg.space.Spaces;

/**
 * Vista del mes: planeado vs real por categoría, totales por moneda y un total consolidado
 * en dólares (los colones se convierten con el tipo de venta del día de cada movimiento),
 * más la regla 50/30/20 sobre los ingresos.
 */
@Service
public class SummaryService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcClient jdbc;
    private final Spaces spaces;

    SummaryService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    public record CategoryLine(UUID categoryId, String name, CategoryGroup group, BigDecimal plannedCrc,
                               BigDecimal plannedUsd, BigDecimal spentCrc, BigDecimal spentUsd,
                               BigDecimal spentTotalUsd, BigDecimal remainingCrc, String status) {}

    public record Totals(BigDecimal crc, BigDecimal usd, BigDecimal totalUsd) {}

    public record Summary(YearMonth month, BigDecimal usdSellRate, boolean missingRates, List<CategoryLine> categories,
                          Map<CategoryGroup, Totals> byGroup, Totals income, Totals expenses,
                          Map<CategoryGroup, BigDecimal> percentOfIncome, Totals saved) {}

    @Transactional(readOnly = true)
    public Summary month(UUID spaceId, YearMonth month) {
        spaces.requireMember(spaceId);
        BigDecimal rate = jdbc.sql("SELECT usd_sell_rate(?)").param(month.atEndOfMonth())
            .query(BigDecimal.class).optional().orElse(null);

        boolean[] missing = {false};
        // Lo ahorrado: dinero movido a un CDP o a la cuenta de una meta (V18). No es un gasto.
        BigDecimal[] moved = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.sql("SELECT currency, amount FROM period_savings(?, ?, ?)")
            .params(spaceId, month.atDay(1), month.plusMonths(1).atDay(1))
            .query(rs -> {
                moved["USD".equals(rs.getString(1).strip()) ? 1 : 0] = rs.getBigDecimal(2);
            });
        BigDecimal savedUsd = moved[1].add(rate == null ? BigDecimal.ZERO
            : moved[0].divide(rate, 2, RoundingMode.HALF_UP));
        missing[0] |= rate == null && moved[0].signum() != 0;
        Totals saved = new Totals(moved[0], moved[1], savedUsd);

        List<CategoryLine> lines = new ArrayList<>();
        // La línea «Ahorro» del presupuesto mide lo que se movió a un CDP o a una meta: así un
        // plan de «ahorrar ₡200 000» se cumple con la transferencia (ya no se ahorra con un gasto).
        boolean[] savingsInLine = {false};
        jdbc.sql("""
                WITH tx AS (
                    SELECT category_id, currency, amount,
                           CASE WHEN currency = 'USD' THEN amount
                                ELSE amount / usd_sell_rate(tx_date) END AS amount_usd
                      FROM transaction
                     WHERE space_id = ? AND tx_date >= ? AND tx_date < ?
                )
                SELECT c.id, c.name, c.category_group,
                       coalesce(bl.planned_amount, 0) AS planned_crc,
                       coalesce(sum(tx.amount) FILTER (WHERE tx.currency = 'CRC'), 0) AS spent_crc,
                       coalesce(sum(tx.amount) FILTER (WHERE tx.currency = 'USD'), 0) AS spent_usd,
                       coalesce(sum(tx.amount_usd), 0) AS spent_total_usd,
                       coalesce(bool_or(tx.amount_usd IS NULL AND tx.amount IS NOT NULL), false) AS missing_rate
                  FROM category c
                  LEFT JOIN budget b ON b.space_id = c.space_id AND b.year = ? AND b.month = ?
                  LEFT JOIN budget_line bl ON bl.budget_id = b.id AND bl.category_id = c.id
                  LEFT JOIN tx ON tx.category_id = c.id
                 WHERE c.space_id = ?
                 GROUP BY c.id, c.name, c.category_group, bl.planned_amount
                 ORDER BY c.category_group, lower(c.name)""")
            .params(spaceId, month.atDay(1), month.plusMonths(1).atDay(1), month.getYear(), month.getMonthValue(), spaceId)
            .query(rs -> {
                BigDecimal planned = rs.getBigDecimal("planned_crc");
                BigDecimal plannedUsd = rate == null ? null : planned.divide(rate, 2, RoundingMode.HALF_UP);
                BigDecimal totalUsd = rs.getBigDecimal("spent_total_usd").setScale(2, RoundingMode.HALF_UP);
                missing[0] |= rs.getBoolean("missing_rate");
                // El estado se calcula EN COLONES (la moneda del presupuesto). Así, gastar exactamente
                // lo planeado en colones da exactamente 100 %, sin redondeos de conversión.
                BigDecimal spentCrc = rs.getBigDecimal("spent_crc");
                BigDecimal spentUsd = rs.getBigDecimal("spent_usd");
                BigDecimal lineUsd = totalUsd;
                if (!savingsInLine[0] && "AHORRO".equals(rs.getString("category_group"))
                        && "ahorro".equalsIgnoreCase(rs.getString("name").strip())) {
                    savingsInLine[0] = true;
                    spentCrc = spentCrc.add(moved[0]);
                    spentUsd = spentUsd.add(moved[1]);
                    lineUsd = totalUsd.add(savedUsd);
                }
                BigDecimal spentAsCrc = rate == null ? spentCrc
                    : spentCrc.add(spentUsd.multiply(rate)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal remaining = planned.signum() == 0 ? null : planned.subtract(spentAsCrc);
                lines.add(new CategoryLine(rs.getObject("id", UUID.class), rs.getString("name"),
                    CategoryGroup.valueOf(rs.getString("category_group")), planned, plannedUsd,
                    spentCrc, spentUsd, lineUsd, remaining, status(planned, spentAsCrc)));
            });

        Map<CategoryGroup, Totals> byGroup = new EnumMap<>(CategoryGroup.class);
        for (CategoryGroup group : CategoryGroup.values()) {
            byGroup.put(group, sum(lines.stream().filter(l -> l.group() == group).toList()));
        }
        Totals income = byGroup.get(CategoryGroup.INGRESO);
        Totals expenses = sum(lines.stream().filter(l -> l.group() != CategoryGroup.INGRESO).toList());
        if (savingsInLine[0]) {
            // Lo ahorrado está en la línea «Ahorro», pero no es un gasto.
            expenses = new Totals(expenses.crc().subtract(saved.crc()), expenses.usd().subtract(saved.usd()),
                expenses.totalUsd().subtract(saved.totalUsd()));
        }


        Map<CategoryGroup, BigDecimal> percent = new EnumMap<>(CategoryGroup.class);
        if (income.totalUsd().signum() > 0) {
            for (CategoryGroup group : List.of(CategoryGroup.NECESIDAD, CategoryGroup.DESEO, CategoryGroup.AHORRO)) {
                BigDecimal amount = byGroup.get(group).totalUsd();
                if (group == CategoryGroup.AHORRO && !savingsInLine[0]) {
                    amount = amount.add(savedUsd);
                }
                percent.put(group, amount.multiply(HUNDRED).divide(income.totalUsd(), 1, RoundingMode.HALF_UP));
            }
        }
        return new Summary(month, rate, missing[0], lines, byGroup, income, expenses, percent, saved);
    }

    private static Totals sum(List<CategoryLine> lines) {
        BigDecimal crc = BigDecimal.ZERO, usd = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (CategoryLine l : lines) {
            crc = crc.add(l.spentCrc());
            usd = usd.add(l.spentUsd());
            total = total.add(l.spentTotalUsd());
        }
        return new Totals(crc, usd, total);
    }

    /**
     * OK (< 90 %), CERCA (90 % a menos de 100 %), AL_LIMITE (exactamente lo planeado),
     * EXCEDIDO (más de lo planeado) o SIN_PRESUPUESTO. Montos en colones.
     */
    static String status(BigDecimal plannedCrc, BigDecimal spentCrc) {
        if (plannedCrc == null || plannedCrc.signum() == 0) {
            return "SIN_PRESUPUESTO";
        }
        int vsPlanned = spentCrc.compareTo(plannedCrc);
        if (vsPlanned > 0) {
            return "EXCEDIDO";
        }
        if (vsPlanned == 0) {
            return "AL_LIMITE";
        }
        // gastado ≥ 90 % de lo planeado, sin dividir (una división redondeada convertiría 89,9999 % en 90 %).
        return spentCrc.multiply(BigDecimal.TEN).compareTo(plannedCrc.multiply(new BigDecimal("9"))) >= 0 ? "CERCA" : "OK";
    }
}

package com.casaayg.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.debt.DebtService;
import com.casaayg.debt.DebtService.DebtView;
import com.casaayg.domain.CategoryGroup;
import com.casaayg.domain.Money;
import com.casaayg.goal.GoalService;
import com.casaayg.goal.GoalService.GoalView;
import com.casaayg.place.PlaceService;
import com.casaayg.place.PlaceService.PlaceView;
import com.casaayg.recurring.RecurringService;
import com.casaayg.recurring.RecurringService.RecurringView;
import com.casaayg.space.Spaces;
import com.casaayg.summary.SummaryService;
import com.casaayg.summary.SummaryService.CategoryLine;

/**
 * «¿Voy bien?»: las tres partes del dinero y cuatro señales de salud financiera, calculadas
 * con las reglas de PRODUCT.md. Todo en colones (los dólares, al tipo de cambio del BCCR),
 * salvo lo que es de una deuda o meta en su propia moneda.
 */
@Service
public class HealthService {

    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");
    /** La categoría de los abonos (como en la pantalla de deudas). */
    static final String DEBT_CATEGORY = "Pago de deudas";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Meses cerrados que se promedian para comparar la tasa de ahorro (y el ritmo de abonos). */
    private static final int BASELINE = 3;

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final PlaceService places;
    private final SummaryService summaries;
    private final RecurringService recurring;
    private final DebtService debts;
    private final GoalService goals;

    HealthService(JdbcClient jdbc, Spaces spaces, PlaceService places, SummaryService summaries,
                  RecurringService recurring, DebtService debts, GoalService goals) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.places = places;
        this.summaries = summaries;
        this.recurring = recurring;
        this.debts = debts;
        this.goals = goals;
    }

    /**
     * Ahorrado / Disponible / Te sobra, en colones. {@code pending}: lo que todavía falta pagar
     * este mes (presupuesto, pagos fijos y cuotas, sin contar dos veces lo mismo).
     * {@code missingRate}: hay dólares pero no hay tipo de cambio, y no se sumaron.
     */
    public record MoneyParts(BigDecimal saved, BigDecimal available, BigDecimal pending, BigDecimal leftover,
                             boolean missingRate) {}

    /** Tasa de ahorro del mes en curso y el promedio de los meses cerrados con ingresos. */
    public record SavingsRate(Integer month, BigDecimal savedMonth, BigDecimal incomeMonth, Integer average,
                              int averageMonths) {}

    /** El fondo de emergencia: cuántos meses de gastos cubre lo que tiene. */
    public record Emergency(UUID goalId, String name, Money.Currency currency, BigDecimal saved,
                            BigDecimal monthlyExpenses, BigDecimal monthsCovered, int targetMonths) {}

    public enum DebtStatus { A_TIEMPO, ATRASADA, SIN_FECHA }

    /**
     * Una deuda viva. Con fecha límite: cuánto hace falta al mes contra el promedio de abonos.
     * Sin fecha: a ese promedio, cuándo se terminaría ({@code estimatedEnd}, null sin abonos).
     */
    public record DebtHealth(UUID id, String creditor, Money.Currency currency, BigDecimal remaining,
                             BigDecimal monthlyAverage, BigDecimal monthlyNeeded, LocalDate dueDate,
                             LocalDate estimatedEnd, DebtStatus status) {}

    public record Debts(BigDecimal remainingCrc, List<DebtHealth> items) {}

    /** Metas con fecha: cuántas van a tiempo. Las que no tienen fecha no se juzgan. */
    public record Goals(int judged, int onTime, List<String> behind, int withoutDate) {}

    public record Health(LocalDate today, MoneyParts money, SavingsRate savingsRate, Emergency emergency,
                         Debts debts, Goals goals) {}

    @Transactional(readOnly = true)
    public Health health(UUID spaceId) {
        spaces.requireMember(spaceId);
        LocalDate today = LocalDate.now(COSTA_RICA);
        YearMonth month = YearMonth.from(today);
        BigDecimal rate = jdbc.sql("SELECT usd_sell_rate(?)").param(today)
            .query(BigDecimal.class).optional().orElse(null);
        List<DebtView> debtList = debts.list(spaceId).stream()
            .filter(d -> d.settledOn() == null && d.remaining().signum() > 0).toList();
        List<GoalView> goalList = goals.list(spaceId);
        return new Health(today,
            moneyParts(spaceId, month, rate, debtList),
            savingsRate(spaceId, month),
            emergency(goalList),
            debtHealth(spaceId, month, today, rate, debtList),
            goalHealth(goalList, month));
    }

    // --- Las tres partes -------------------------------------------------------------------

    private MoneyParts moneyParts(UUID spaceId, YearMonth month, BigDecimal rate, List<DebtView> debtList) {
        boolean[] missing = {false};
        BigDecimal saved = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        for (PlaceView p : places.list(spaceId)) {
            if (p.archived()) {
                continue;
            }
            BigDecimal crc = toCrc(p.balance(), p.currency(), rate, missing);
            if (p.savings()) {
                saved = saved.add(crc);
            } else {
                available = available.add(crc);
            }
        }

        // Lo que falta pagar este mes, por categoría: lo mayor entre lo que queda del presupuesto
        // y lo pendiente de sus pagos fijos (un pago fijo también es parte de su presupuesto).
        Map<UUID, BigDecimal> budgetLeft = new HashMap<>();
        for (CategoryLine line : summaries.month(spaceId, month).categories()) {
            if (line.group() != CategoryGroup.INGRESO && line.remainingCrc() != null && line.remainingCrc().signum() > 0) {
                budgetLeft.put(line.categoryId(), line.remainingCrc());
            }
        }
        Map<UUID, BigDecimal> committed = new HashMap<>();
        for (RecurringView r : recurring.list(spaceId, month)) {
            if (r.active() && r.paidTransactionId() == null && r.expectedAmount() != null && r.dueDate() != null) {
                committed.merge(r.categoryId(), toCrc(r.expectedAmount(), r.currency(), rate, missing), BigDecimal::add);
            }
        }
        // Las cuotas de deudas van con la categoría de los abonos, si existe; si no, aparte.
        BigDecimal installments = BigDecimal.ZERO;
        for (DebtView d : debtList) {
            installments = installments.add(toCrc(installmentLeft(d), d.currency(), rate, missing));
        }
        UUID debtCategory = jdbc.sql("SELECT id FROM category WHERE space_id = ? AND name = ? AND active")
            .params(spaceId, DEBT_CATEGORY).query(UUID.class).optional().orElse(null);
        BigDecimal outside = BigDecimal.ZERO;
        if (installments.signum() > 0) {
            if (debtCategory != null) {
                committed.merge(debtCategory, installments, BigDecimal::add);
            } else {
                outside = installments;
            }
        }
        BigDecimal pending = outside;
        java.util.Set<UUID> categories = new java.util.HashSet<>(budgetLeft.keySet());
        categories.addAll(committed.keySet());
        for (UUID c : categories) {
            pending = pending.add(budgetLeft.getOrDefault(c, BigDecimal.ZERO).max(committed.getOrDefault(c, BigDecimal.ZERO)));
        }
        return new MoneyParts(round(saved), round(available), round(pending), round(available.subtract(pending)),
            missing[0]);
    }

    /** Lo que falta de la cuota de este mes (0 si ya se abonó o no hay cuota). */
    static BigDecimal installmentLeft(DebtView d) {
        if (d.installment() == null || d.remaining().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return d.installment().subtract(d.paidThisMonth()).max(BigDecimal.ZERO).min(d.remaining());
    }

    // --- Tasa de ahorro --------------------------------------------------------------------

    private SavingsRate savingsRate(UUID spaceId, YearMonth month) {
        BigDecimal savedMonth = BigDecimal.ZERO;
        BigDecimal incomeMonth = BigDecimal.ZERO;
        List<Integer> previous = new ArrayList<>();
        // El mes en curso y, hacia atrás, hasta 3 meses cerrados con ingresos (se buscan en un año).
        for (int i = 0; i <= 12 && previous.size() < BASELINE; i++) {
            YearMonth m = month.minusMonths(i);
            BigDecimal income = jdbc.sql("""
                    SELECT COALESCE(sum(CASE WHEN t.currency = 'CRC' THEN t.amount
                                             ELSE COALESCE(t.amount * usd_sell_rate(t.tx_date), 0) END), 0)
                      FROM transaction t JOIN category c ON c.id = t.category_id
                     WHERE t.space_id = ? AND c.category_group = 'INGRESO' AND t.tx_date >= ? AND t.tx_date < ?""")
                .params(spaceId, m.atDay(1), m.plusMonths(1).atDay(1)).query(BigDecimal.class).single();
            BigDecimal saved = jdbc.sql("SELECT period_savings_net_crc(?, ?, ?)")
                .params(spaceId, m.atDay(1), m.plusMonths(1).atDay(1)).query(BigDecimal.class).single();
            if (i == 0) {
                savedMonth = saved;
                incomeMonth = income;
            } else if (income.signum() > 0) {
                previous.add(percent(saved, income));
            }
        }
        Integer average = previous.isEmpty() ? null
            : (int) Math.round(previous.stream().mapToInt(Integer::intValue).average().orElse(0));
        return new SavingsRate(incomeMonth.signum() > 0 ? percent(savedMonth, incomeMonth) : null,
            round(savedMonth), round(incomeMonth), average, previous.size());
    }

    private static int percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, 0, RoundingMode.HALF_UP).intValue();
    }

    // --- Fondo de emergencia ---------------------------------------------------------------

    private static Emergency emergency(List<GoalView> goalList) {
        return goalList.stream()
            .filter(g -> g.kind() == GoalService.Kind.EMERGENCIA
                && (g.status() == GoalService.Status.ACTIVA || g.status() == GoalService.Status.PAUSADA))
            .min(Comparator.comparingInt(GoalView::priority))
            .map(g -> new Emergency(g.id(), g.name(), g.currency(), g.saved(), g.monthlyExpenses(),
                g.monthlyExpenses() == null || g.monthlyExpenses().signum() <= 0 ? null
                    : g.saved().max(BigDecimal.ZERO).divide(g.monthlyExpenses(), 1, RoundingMode.DOWN),
                g.emergencyMonths() == null ? 0 : g.emergencyMonths()))
            .orElse(null);
    }

    // --- Deudas ----------------------------------------------------------------------------

    private Debts debtHealth(UUID spaceId, YearMonth month, LocalDate today, BigDecimal rate, List<DebtView> debtList) {
        boolean[] ignored = {false};
        BigDecimal total = BigDecimal.ZERO;
        List<DebtHealth> items = new ArrayList<>();
        for (DebtView d : debtList) {
            total = total.add(toCrc(d.remaining(), d.currency(), rate, ignored));
            BigDecimal average = monthlyAverage(spaceId, d.id(), month);
            BigDecimal needed = null;
            LocalDate end = null;
            DebtStatus status;
            if (d.dueDate() != null) {
                long months = Math.max(1, ChronoUnit.MONTHS.between(month.atDay(1), d.dueDate().withDayOfMonth(1)));
                needed = d.remaining().divide(BigDecimal.valueOf(months), 2, RoundingMode.UP);
                status = !d.dueDate().isBefore(today) && average.compareTo(needed) >= 0
                    ? DebtStatus.A_TIEMPO : DebtStatus.ATRASADA;
            } else {
                status = DebtStatus.SIN_FECHA;
            }
            if (average.signum() > 0) {
                long months = d.remaining().divide(average, 0, RoundingMode.CEILING).longValue();
                end = months <= 600 ? month.plusMonths(months).atDay(1) : null;
            }
            items.add(new DebtHealth(d.id(), d.creditor(), d.currency(), d.remaining(), average, needed, d.dueDate(),
                end, status));
        }
        return new Debts(round(total), items);
    }

    /**
     * Promedio mensual de abonos: lo abonado en los últimos meses (hasta 3, contando el actual)
     * entre los meses que van desde el primer abono de esa ventana. Sin abonos, 0.
     */
    private BigDecimal monthlyAverage(UUID spaceId, UUID debtId, YearMonth month) {
        YearMonth from = month.minusMonths(BASELINE - 1);
        return jdbc.sql("""
                SELECT sum(t.amount) AS total, min(t.tx_date) AS first_on
                  FROM transaction t
                 WHERE t.space_id = ? AND t.debt_id = ? AND t.tx_date >= ? AND t.tx_date < ?""")
            .params(spaceId, debtId, from.atDay(1), month.plusMonths(1).atDay(1))
            .query((rs, n) -> {
                BigDecimal sum = rs.getBigDecimal("total");
                LocalDate first = rs.getObject("first_on", LocalDate.class);
                if (sum == null || first == null) {
                    return BigDecimal.ZERO;
                }
                long months = ChronoUnit.MONTHS.between(YearMonth.from(first), month) + 1;
                return sum.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
            })
            .single();
    }

    // --- Metas -----------------------------------------------------------------------------

    private static Goals goalHealth(List<GoalView> goalList, YearMonth month) {
        int judged = 0;
        int onTime = 0;
        int withoutDate = 0;
        List<String> behind = new ArrayList<>();
        for (GoalView g : goalList) {
            if (g.kind() != GoalService.Kind.NORMAL || g.status() != GoalService.Status.ACTIVA
                    || g.remaining().signum() <= 0) {
                continue;
            }
            if (g.targetDate() == null) {
                withoutDate++;
                continue;
            }
            judged++;
            boolean late = YearMonth.from(g.targetDate()).isBefore(month);
            boolean enough = g.monthlyNeeded() == null || g.monthlyPace().compareTo(g.monthlyNeeded()) >= 0;
            if (!late && enough) {
                onTime++;
            } else {
                behind.add(g.name());
            }
        }
        return new Goals(judged, onTime, behind, withoutDate);
    }

    // --- Utilidades ------------------------------------------------------------------------

    private static BigDecimal toCrc(BigDecimal amount, Money.Currency currency, BigDecimal rate, boolean[] missing) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (currency == Money.Currency.CRC) {
            return amount;
        }
        if (rate == null) {
            missing[0] |= amount.signum() != 0;
            return BigDecimal.ZERO;
        }
        return amount.multiply(rate);
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}

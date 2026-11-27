package com.casaayg.goal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.goal.GoalService.GoalView;
import com.casaayg.goal.GoalService.Status;
import com.casaayg.history.HistoryService;
import com.casaayg.history.HistoryService.MonthTotals;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.space.Spaces;

/**
 * El planeador de metas (PRODUCT.md, decisiones del 2026-11-27). Nada es obligatorio y nada se
 * aparta solo: responde «si apartamos tanto al mes, ¿cuándo llegamos?» y, si la pareja guarda un
 * plan, compara cada mes lo planeado con lo que de verdad entró a las metas.
 *
 * <ul>
 *   <li>Lo que se puede apartar viene lleno con el promedio de lo que sobró (ingresos menos
 *       gastos) en los últimos 3 meses completos.</li>
 *   <li>Reparto, mes a mes y por prioridad: primero cada meta con fecha recibe lo que necesita
 *       para llegar a tiempo, mientras alcance; lo que sobra va en cascada a la meta más
 *       importante sin terminar.</li>
 *   <li>La recomendación: el mínimo al mes para que todas las metas con fecha lleguen a tiempo.</li>
 * </ul>
 * Todo en colones; las metas en dólares, al tipo de cambio de hoy.
 */
@Service
public class PlannerService {

    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");
    /** Hasta dónde se mira hacia adelante (50 años). */
    static final int HORIZON = 600;
    static final int BASELINE = 3;
    static final BigDecimal ROUND_TO = new BigDecimal("1000");

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final GoalService goals;
    private final HistoryService history;

    PlannerService(JdbcClient jdbc, Spaces spaces, GoalService goals, HistoryService history) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.goals = goals;
        this.history = history;
    }

    /** Una meta para el cálculo: lo que le falta, en colones, y su fecha (si tiene). */
    record Input(UUID id, BigDecimal remainingCrc, YearMonth target) {}

    /** Lo que el reparto le da a una meta el primer mes y cuándo llegaría (null: no llega). */
    record Allocation(UUID id, BigDecimal firstMonthCrc, YearMonth eta) {}

    public record Item(UUID goalId, String name, Money.Currency currency, BigDecimal remaining, LocalDate targetDate,
                       BigDecimal monthlyCrc, LocalDate eta, Boolean onTime) {}

    /**
     * @param averageSurplus  lo que en promedio sobró (null si todavía no hay meses completos)
     * @param recommended     el mínimo al mes para llegar a tiempo a todas las metas con fecha
     *                        (null si ninguna tiene fecha: entonces se recomienda el promedio)
     * @param recommendedGoal la meta con fecha que marca ese mínimo, y su fecha
     * @param skipped         metas en dólares que no entran porque falta el tipo de cambio
     */
    public record Planner(BigDecimal averageSurplus, int averageMonths, BigDecimal recommended,
                          String recommendedGoal, LocalDate recommendedBy, BigDecimal monthly, List<Item> items,
                          List<String> skipped) {}

    public record MonthProgress(YearMonth month, BigDecimal planned, BigDecimal actual, boolean partial) {}

    public record Progress(Instant createdAt, YearMonth startMonth, BigDecimal monthly, List<MonthProgress> months) {}

    // --- «¿Cuándo llego?» -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Planner planner(UUID spaceId, BigDecimal monthlyOverride) {
        spaces.requireMember(spaceId);
        YearMonth now = YearMonth.now(COSTA_RICA);
        List<BigDecimal> surpluses = history.history(spaceId, now).months().stream()
            .filter(m -> m.month().isBefore(now))
            .filter(m -> m.income().signum() != 0 || m.expenses().signum() != 0)
            .map(m -> m.income().subtract(m.expenses()))
            .toList();
        List<BigDecimal> last = surpluses.subList(Math.max(0, surpluses.size() - BASELINE), surpluses.size());
        BigDecimal average = last.isEmpty() ? null : roundDown(last.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(last.size()), 2, RoundingMode.DOWN).max(BigDecimal.ZERO));

        Context c = context(spaceId, now);
        BigDecimal recommended = recommended(c.inputs(), now);
        Input binding = recommended == null ? null : bindingGoal(c.inputs(), recommended, now);
        BigDecimal monthly = monthlyOverride != null ? monthlyOverride
            : recommended != null ? recommended : average != null ? average : BigDecimal.ZERO;
        if (monthly.signum() < 0 || monthly.compareTo(Money.MAX) > 0) {
            throw new IllegalArgumentException("monto fuera de rango");
        }
        return new Planner(average, last.size(), recommended,
            binding == null ? null : c.names().get(binding.id()),
            binding == null ? null : binding.target().atEndOfMonth(),
            monthly, items(c, simulate(c.inputs(), monthly, now)), c.skipped());
    }

    private record Context(List<Input> inputs, Map<UUID, GoalView> views, Map<UUID, String> names, List<String> skipped) {}

    private Context context(UUID spaceId, YearMonth now) {
        BigDecimal rate = jdbc.sql("SELECT usd_sell_rate(?)").param(LocalDate.now(COSTA_RICA))
            .query(BigDecimal.class).optional().orElse(null);
        List<Input> inputs = new ArrayList<>();
        Map<UUID, GoalView> views = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (GoalView g : goals.list(spaceId)) {
            if (g.status() != Status.ACTIVA || g.target().signum() <= 0) {
                continue;
            }
            BigDecimal remainingCrc = g.remaining();
            if (g.currency() == Money.Currency.USD) {
                if (rate == null) {
                    skipped.add(g.name());
                    continue;
                }
                remainingCrc = remainingCrc.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            }
            YearMonth target = g.targetDate() == null ? null : YearMonth.from(g.targetDate());
            inputs.add(new Input(g.id(), remainingCrc, target != null && target.isBefore(now) ? now : target));
            views.put(g.id(), g);
            names.put(g.id(), g.name());
        }
        return new Context(inputs, views, names, skipped);
    }

    private static List<Item> items(Context c, List<Allocation> allocations) {
        List<Item> out = new ArrayList<>();
        for (Allocation a : allocations) {
            GoalView g = c.views().get(a.id());
            LocalDate eta = a.eta() == null ? null : a.eta().atEndOfMonth();
            Boolean onTime = g.targetDate() == null || g.remaining().signum() <= 0 ? null
                : a.eta() != null && !a.eta().isAfter(YearMonth.from(g.targetDate()));
            out.add(new Item(g.id(), g.name(), g.currency(), g.remaining(), g.targetDate(), a.firstMonthCrc(), eta, onTime));
        }
        return out;
    }

    /**
     * El reparto, mes a mes. Cada mes: (1) en orden de prioridad, cada meta con fecha recibe lo
     * que le falta dividido entre los meses que le quedan, mientras alcance; (2) lo que sobra va
     * en cascada a la meta más importante sin terminar. Una meta que termina libera su parte.
     */
    static List<Allocation> simulate(List<Input> goals, BigDecimal monthly, YearMonth start) {
        Map<UUID, BigDecimal> remaining = new LinkedHashMap<>();
        Map<UUID, BigDecimal> first = new LinkedHashMap<>();
        Map<UUID, YearMonth> eta = new LinkedHashMap<>();
        for (Input g : goals) {
            remaining.put(g.id(), g.remainingCrc().max(BigDecimal.ZERO));
            first.put(g.id(), BigDecimal.ZERO);
            if (g.remainingCrc().signum() <= 0) {
                eta.put(g.id(), start);
            }
        }
        for (int i = 0; i < HORIZON && monthly.signum() > 0 && eta.size() < goals.size(); i++) {
            YearMonth month = start.plusMonths(i);
            BigDecimal budget = monthly;
            Map<UUID, BigDecimal> given = new LinkedHashMap<>();
            for (Input g : goals) {
                BigDecimal left = remaining.get(g.id());
                if (g.target() == null || left.signum() <= 0 || month.isAfter(g.target()) || budget.signum() <= 0) {
                    continue;
                }
                long monthsLeft = g.target().getYear() * 12L + g.target().getMonthValue()
                    - (month.getYear() * 12L + month.getMonthValue()) + 1;
                BigDecimal need = left.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.UP);
                BigDecimal give = need.min(budget).min(left);
                budget = budget.subtract(give);
                given.merge(g.id(), give, BigDecimal::add);
                remaining.put(g.id(), left.subtract(give));
            }
            for (Input g : goals) {
                BigDecimal left = remaining.get(g.id());
                if (left.signum() <= 0 || budget.signum() <= 0) {
                    continue;
                }
                BigDecimal give = left.min(budget);
                budget = budget.subtract(give);
                given.merge(g.id(), give, BigDecimal::add);
                remaining.put(g.id(), left.subtract(give));
            }
            for (Input g : goals) {
                if (i == 0) {
                    first.put(g.id(), given.getOrDefault(g.id(), BigDecimal.ZERO));
                }
                if (!eta.containsKey(g.id()) && remaining.get(g.id()).signum() <= 0) {
                    eta.put(g.id(), month);
                }
            }
        }
        return goals.stream().map(g -> new Allocation(g.id(), first.get(g.id()), eta.get(g.id()))).toList();
    }

    /** El mínimo al mes (redondeado a miles hacia arriba) para que todas las metas con fecha lleguen a tiempo. */
    static BigDecimal recommended(List<Input> goals, YearMonth start) {
        List<Input> dated = goals.stream().filter(g -> g.target() != null && g.remainingCrc().signum() > 0).toList();
        if (dated.isEmpty()) {
            return null;
        }
        long low = 0;
        long high = goals.stream().map(Input::remainingCrc).reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(ROUND_TO, 0, RoundingMode.CEILING).longValue();
        while (low < high) {
            long mid = (low + high) / 2;
            if (allOnTime(goals, BigDecimal.valueOf(mid).multiply(ROUND_TO), start)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return BigDecimal.valueOf(high).multiply(ROUND_TO).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean allOnTime(List<Input> goals, BigDecimal monthly, YearMonth start) {
        List<Allocation> result = simulate(goals, monthly, start);
        for (int i = 0; i < goals.size(); i++) {
            Input g = goals.get(i);
            YearMonth eta = result.get(i).eta();
            if (g.target() != null && g.remainingCrc().signum() > 0 && (eta == null || eta.isAfter(g.target()))) {
                return false;
            }
        }
        return true;
    }

    /** La meta con fecha que obliga al mínimo: con ₡1.000 menos, es la primera que no llega. */
    static Input bindingGoal(List<Input> goals, BigDecimal recommended, YearMonth start) {
        BigDecimal less = recommended.subtract(ROUND_TO).max(BigDecimal.ZERO);
        List<Allocation> result = simulate(goals, less, start);
        Input latest = null;
        for (int i = 0; i < goals.size(); i++) {
            Input g = goals.get(i);
            if (g.target() == null || g.remainingCrc().signum() <= 0) {
                continue;
            }
            YearMonth eta = result.get(i).eta();
            if (recommended.signum() > 0 && (eta == null || eta.isAfter(g.target()))) {
                return g;
            }
            if (latest == null || g.target().isAfter(latest.target())) {
                latest = g;
            }
        }
        return latest;
    }

    private static BigDecimal roundDown(BigDecimal amount) {
        return amount.divide(ROUND_TO, 0, RoundingMode.DOWN).multiply(ROUND_TO).setScale(2, RoundingMode.UNNECESSARY);
    }

    // --- El plan (opcional) y «¿Voy como planeé?» --------------------------------------------

    /** Guarda (o reemplaza) el plan con ese monto al mes. */
    @Transactional
    public Progress save(UUID spaceId, BigDecimal monthly) {
        spaces.requireWritable(spaceId);
        YearMonth now = YearMonth.now(COSTA_RICA);
        Planner p = planner(spaceId, monthly);
        jdbc.sql("DELETE FROM goal_plan WHERE space_id = ?").param(spaceId).update();
        UUID plan = jdbc.sql("""
                INSERT INTO goal_plan (space_id, created_by, start_month, monthly_crc) VALUES (?, ?, ?, ?)
                RETURNING id""")
            .params(spaceId, CurrentUser.require(), now.atDay(1), p.monthly()).query(UUID.class).single();
        for (Item i : p.items()) {
            jdbc.sql("INSERT INTO goal_plan_item (plan_id, goal_id, space_id, monthly_crc, eta) VALUES (?, ?, ?, ?, ?)")
                .params(plan, i.goalId(), spaceId, i.monthlyCrc(), i.eta()).update();
        }
        return progress(spaceId);
    }

    @Transactional
    public void delete(UUID spaceId) {
        spaces.requireWritable(spaceId);
        jdbc.sql("DELETE FROM goal_plan WHERE space_id = ?").param(spaceId).update();
    }

    /**
     * Mes a mes, desde que se guardó el plan: lo planeado (lo que el plan le daba a sus metas) y
     * lo que de verdad entró a esas metas. Sin juicios: solo los dos números. null sin plan.
     */
    @Transactional(readOnly = true)
    public Progress progress(UUID spaceId) {
        spaces.requireMember(spaceId);
        record Plan(UUID id, Instant createdAt, LocalDate start, BigDecimal monthly) {}
        Plan plan = jdbc.sql("SELECT id, created_at, start_month, monthly_crc FROM goal_plan WHERE space_id = ?")
            .param(spaceId)
            .query((rs, n) -> new Plan(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                rs.getObject(3, LocalDate.class), rs.getBigDecimal(4)))
            .optional().orElse(null);
        if (plan == null) {
            return null;
        }
        LocalDate today = LocalDate.now(COSTA_RICA);
        YearMonth now = YearMonth.from(today);
        BigDecimal rate = jdbc.sql("SELECT usd_sell_rate(?)").param(today).query(BigDecimal.class).optional().orElse(null);
        BigDecimal planned = jdbc.sql("SELECT COALESCE(sum(monthly_crc), 0) FROM goal_plan_item WHERE plan_id = ?")
            .param(plan.id()).query(BigDecimal.class).single();
        List<MonthProgress> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(plan.start()); !m.isAfter(now) && months.size() < 120; m = m.plusMonths(1)) {
            LocalDate before = m.atDay(1).minusDays(1);
            LocalDate end = m.equals(now) ? today : m.atEndOfMonth();
            // Lo que entró a las metas del plan ese mes: su saldo al final menos el del mes anterior.
            BigDecimal actual = jdbc.sql("""
                    SELECT COALESCE(sum((goal_saved(i.goal_id, ?) - goal_saved(i.goal_id, ?))
                                        * CASE WHEN g.currency = 'USD' THEN COALESCE(?, 0) ELSE 1 END), 0)
                      FROM goal_plan_item i JOIN goal g ON g.id = i.goal_id
                     WHERE i.plan_id = ?""")
                .params(end, before, rate, plan.id()).query(BigDecimal.class).single();
            months.add(new MonthProgress(m, planned, actual.setScale(2, RoundingMode.HALF_UP), m.equals(now)));
        }
        return new Progress(plan.createdAt(), YearMonth.from(plan.start()), plan.monthly(), months);
    }
}

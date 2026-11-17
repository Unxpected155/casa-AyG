package com.casaayg.goal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.place.PlaceService;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

/**
 * Metas de ahorro (V10). Lo ahorrado sale del saldo de los lugares ligados (goal_saved en la
 * BD); el objetivo, de goal_target (fijo, o meses de gastos reales para el fondo de emergencia).
 */
@Service
public class GoalService {

    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");
    public static final int MAX_PARTS = 12;

    public enum Kind { NORMAL, EMERGENCIA }

    public enum Status { ACTIVA, PAUSADA, CUMPLIDA, ARCHIVADA }

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final PlaceService places;

    GoalService(JdbcClient jdbc, Spaces spaces, PlaceService places) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.places = places;
    }

    public record Part(String name, BigDecimal amount) {}

    public record Data(String name, Kind kind, BigDecimal targetAmount, Integer emergencyMonths,
                       Money.Currency currency, LocalDate targetDate, List<UUID> placeIds, List<Part> parts) {}

    /**
     * Una meta con su avance. {@code monthlyNeeded}: cuánto apartar por mes para llegar a la fecha.
     * {@code monthlyPace}: lo que entró en promedio a sus lugares en los últimos 3 meses.
     * {@code eta}: a ese ritmo, cuándo se llega (null si no hay ritmo).
     */
    public record GoalView(UUID id, String name, Kind kind, BigDecimal targetAmount, Integer emergencyMonths,
                           Money.Currency currency, LocalDate targetDate, int priority, Status status,
                           BigDecimal target, BigDecimal saved, BigDecimal remaining, int percent,
                           BigDecimal monthlyNeeded, BigDecimal monthlyPace, LocalDate eta,
                           BigDecimal monthlyExpenses, List<UUID> placeIds, List<Part> parts) {}

    record Row(UUID id, String name, Kind kind, BigDecimal targetAmount, Integer emergencyMonths,
               Money.Currency currency, LocalDate targetDate, int priority, Status status, BigDecimal target,
               BigDecimal saved, BigDecimal saved90, BigDecimal expenses) {}

    @Transactional(readOnly = true)
    public List<GoalView> list(UUID spaceId) {
        spaces.requireMember(spaceId);
        LocalDate today = LocalDate.now(COSTA_RICA);
        List<Row> rows = jdbc.sql("""
                SELECT g.id, g.name, g.kind, g.target_amount, g.emergency_months, g.currency, g.target_date,
                       g.priority, g.status, goal_target(g.id, ?) AS target, goal_saved(g.id, ?) AS saved,
                       goal_saved(g.id, ?) AS saved90, monthly_expenses(g.space_id, g.currency, ?) AS expenses
                  FROM goal g WHERE g.space_id = ?
                 ORDER BY CASE g.status WHEN 'ACTIVA' THEN 0 WHEN 'PAUSADA' THEN 1 WHEN 'CUMPLIDA' THEN 2 ELSE 3 END,
                          g.priority, g.created_at""")
            .params(today, today, today.minusDays(90), today, spaceId)
            .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                Kind.valueOf(rs.getString("kind")), rs.getBigDecimal("target_amount"),
                rs.getObject("emergency_months", Integer.class), Money.Currency.valueOf(rs.getString("currency")),
                rs.getObject("target_date", LocalDate.class), rs.getInt("priority"),
                Status.valueOf(rs.getString("status")), rs.getBigDecimal("target"), rs.getBigDecimal("saved"),
                rs.getBigDecimal("saved90"), rs.getBigDecimal("expenses")))
            .list();
        Map<UUID, List<UUID>> placesByGoal = jdbc.sql("""
                SELECT gp.goal_id, gp.place_id FROM goal_place gp JOIN goal g ON g.id = gp.goal_id
                 WHERE g.space_id = ?""")
            .param(spaceId).query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
            .list().stream().collect(Collectors.groupingBy(a -> a[0], Collectors.mapping(a -> a[1], Collectors.toList())));
        Map<UUID, List<Part>> partsByGoal = jdbc.sql("""
                SELECT p.goal_id, p.name, p.amount FROM goal_part p JOIN goal g ON g.id = p.goal_id
                 WHERE g.space_id = ? ORDER BY p.position, p.name""")
            .param(spaceId).query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), new Part(rs.getString(2), rs.getBigDecimal(3))))
            .list().stream().collect(Collectors.groupingBy(Map.Entry::getKey, java.util.LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return rows.stream().map(r -> view(r, today, placesByGoal.getOrDefault(r.id(), List.of()),
            partsByGoal.getOrDefault(r.id(), List.of()))).toList();
    }

    static GoalView view(Row r, LocalDate today, List<UUID> placeIds, List<Part> parts) {
        BigDecimal target = r.target() == null ? BigDecimal.ZERO : r.target();
        BigDecimal saved = r.saved();
        BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO);
        int percent = target.signum() <= 0 ? 0
            : saved.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO).intValue();
        // Plan: con fecha, cuánto por mes (al menos un mes); sin fecha, el ritmo de los últimos 3 meses.
        BigDecimal needed = null;
        if (r.targetDate() != null && remaining.signum() > 0) {
            long months = Math.max(1, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), r.targetDate().withDayOfMonth(1)));
            needed = remaining.divide(BigDecimal.valueOf(months), 2, RoundingMode.UP);
        }
        BigDecimal pace = saved.subtract(r.saved90()).divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        LocalDate eta = null;
        if (remaining.signum() > 0 && pace.signum() > 0) {
            long months = remaining.divide(pace, 0, RoundingMode.CEILING).longValue();
            eta = months <= 600 ? today.plusMonths(months).withDayOfMonth(1) : null;
        }
        return new GoalView(r.id(), r.name(), r.kind(), r.targetAmount(), r.emergencyMonths(), r.currency(),
            r.targetDate(), r.priority(), r.status(), target, saved, remaining, percent, needed,
            pace.signum() > 0 ? pace : BigDecimal.ZERO, eta, r.expenses(), placeIds, parts);
    }

    @Transactional
    public GoalView create(UUID spaceId, Data d) {
        spaces.requireWritable(spaceId);
        validate(spaceId, d);
        int priority = jdbc.sql("SELECT COALESCE(max(priority) + 1, 0) FROM goal WHERE space_id = ?")
            .param(spaceId).query(Integer.class).single();
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO goal (space_id, created_by, name, kind, target_amount, emergency_months, currency,
                                      target_date, priority)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(spaceId, CurrentUser.require(), d.name(), d.kind().name(),
                    d.kind() == Kind.NORMAL ? d.targetAmount() : null, d.kind() == Kind.EMERGENCIA ? d.emergencyMonths() : null,
                    d.currency().name(), d.targetDate(), priority)
                .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Ya hay una meta con ese nombre.");
        }
        replaceChildren(spaceId, id, d);
        return find(spaceId, id);
    }

    @Transactional
    public GoalView update(UUID spaceId, UUID id, Data d) {
        spaces.requireWritable(spaceId);
        Kind current = jdbc.sql("SELECT kind FROM goal WHERE id = ? AND space_id = ?").params(id, spaceId)
            .query(String.class).optional().map(Kind::valueOf).orElseThrow(NotFoundException::new);
        if (current != d.kind()) {
            throw new IllegalArgumentException("el tipo de meta no cambia");
        }
        validate(spaceId, d);
        try {
            jdbc.sql("""
                    UPDATE goal SET name = ?, target_amount = ?, emergency_months = ?, currency = ?, target_date = ?
                     WHERE id = ? AND space_id = ?""")
                .params(d.name(), d.kind() == Kind.NORMAL ? d.targetAmount() : null,
                    d.kind() == Kind.EMERGENCIA ? d.emergencyMonths() : null, d.currency().name(), d.targetDate(),
                    id, spaceId)
                .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Ya hay una meta con ese nombre.");
        }
        replaceChildren(spaceId, id, d);
        return find(spaceId, id);
    }

    @Transactional
    public GoalView setStatus(UUID spaceId, UUID id, Status status) {
        spaces.requireWritable(spaceId);
        int rows = jdbc.sql("""
                UPDATE goal SET status = ?, achieved_at = CASE WHEN ? = 'CUMPLIDA' THEN COALESCE(achieved_at, now())
                                                           ELSE achieved_at END
                 WHERE id = ? AND space_id = ?""")
            .params(status.name(), status.name(), id, spaceId).update();
        if (rows == 0) {
            throw new NotFoundException();
        }
        return find(spaceId, id);
    }

    /** Nuevo orden de prioridad: la lista completa de metas del espacio, de la más importante a la menos. */
    @Transactional
    public List<GoalView> reorder(UUID spaceId, List<UUID> ids) {
        spaces.requireWritable(spaceId);
        List<UUID> existing = jdbc.sql("SELECT id FROM goal WHERE space_id = ?").param(spaceId).query(UUID.class).list();
        if (ids.size() != existing.size() || !ids.containsAll(existing)) {
            throw new IllegalArgumentException("el orden debe incluir todas las metas");
        }
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("UPDATE goal SET priority = ? WHERE id = ? AND space_id = ?").params(i, ids.get(i), spaceId).update();
        }
        return list(spaceId);
    }

    @Transactional
    public void delete(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        if (jdbc.sql("DELETE FROM goal WHERE id = ? AND space_id = ?").params(id, spaceId).update() == 0) {
            throw new NotFoundException();
        }
    }

    private void validate(UUID spaceId, Data d) {
        if (d.kind() == Kind.NORMAL && (d.targetAmount() == null || d.targetAmount().signum() <= 0)) {
            throw new IllegalArgumentException("monto objetivo");
        }
        if (d.kind() == Kind.NORMAL) {
            new Money(d.targetAmount(), d.currency());
        }
        if (d.kind() == Kind.EMERGENCIA && (d.emergencyMonths() == null || d.emergencyMonths() < 1 || d.emergencyMonths() > 12)) {
            throw new IllegalArgumentException("meses del fondo de emergencia");
        }
        if (d.parts().size() > MAX_PARTS || d.placeIds().stream().distinct().count() != d.placeIds().size()) {
            throw new IllegalArgumentException("partes o lugares");
        }
        for (UUID place : d.placeIds()) {
            if (places.requirePlace(spaceId, place, false) != d.currency()) {
                throw new ConflictException("Los lugares de una meta tienen que ser de su misma moneda.");
            }
        }
        for (Part p : d.parts()) {
            new Money(p.amount(), d.currency());
            if (p.amount().signum() <= 0) {
                throw new IllegalArgumentException("parte en cero");
            }
        }
    }

    private void replaceChildren(UUID spaceId, UUID goalId, Data d) {
        jdbc.sql("DELETE FROM goal_place WHERE goal_id = ? AND space_id = ?").params(goalId, spaceId).update();
        jdbc.sql("DELETE FROM goal_part WHERE goal_id = ? AND space_id = ?").params(goalId, spaceId).update();
        try {
            for (UUID place : d.placeIds()) {
                jdbc.sql("INSERT INTO goal_place (goal_id, place_id, space_id) VALUES (?, ?, ?)")
                    .params(goalId, place, spaceId).update();
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Ese lugar ya cuenta para otra meta: un lugar va a una sola meta.");
        }
        for (int i = 0; i < d.parts().size(); i++) {
            Part p = d.parts().get(i);
            jdbc.sql("INSERT INTO goal_part (goal_id, space_id, name, amount, position) VALUES (?, ?, ?, ?, ?)")
                .params(goalId, spaceId, p.name(), p.amount(), i).update();
        }
    }

    private GoalView find(UUID spaceId, UUID id) {
        return list(spaceId).stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow(NotFoundException::new);
    }
}

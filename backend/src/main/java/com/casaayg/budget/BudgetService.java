package com.casaayg.budget;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.Money;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

/** Presupuesto mensual por categoría, en colones. */
@Service
public class BudgetService {

    public static final int MAX_LINES = 100;

    private final JdbcClient jdbc;
    private final Spaces spaces;

    BudgetService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    public record Line(UUID categoryId, BigDecimal plannedAmount) {}

    @Transactional(readOnly = true)
    public List<Line> get(UUID spaceId, int year, int month) {
        spaces.requireMember(spaceId);
        return jdbc.sql("""
                SELECT l.category_id, l.planned_amount FROM budget b
                  JOIN budget_line l ON l.budget_id = b.id AND l.space_id = b.space_id
                 WHERE b.space_id = ? AND b.year = ? AND b.month = ?""")
            .params(spaceId, year, month)
            .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getBigDecimal(2)))
            .list();
    }

    /** Reemplaza las líneas del mes por las recibidas. */
    @Transactional
    public List<Line> replace(UUID spaceId, int year, int month, List<Line> lines) {
        spaces.requireWritable(spaceId);
        if (lines.size() > MAX_LINES || new HashSet<>(lines.stream().map(Line::categoryId).toList()).size() != lines.size()) {
            throw new IllegalArgumentException("líneas inválidas");
        }
        for (Line line : lines) {
            new Money(line.plannedAmount(), Money.Currency.CRC); // valida el monto
            jdbc.sql("SELECT 1 FROM category WHERE id = ? AND space_id = ?")
                .params(line.categoryId(), spaceId).query(Integer.class).optional()
                .orElseThrow(NotFoundException::new);
        }
        jdbc.sql("""
                INSERT INTO budget (space_id, created_by, year, month) VALUES (?, ?, ?, ?)
                ON CONFLICT (space_id, year, month) DO NOTHING""")
            .params(spaceId, CurrentUser.require(), year, month).update();
        UUID budgetId = jdbc.sql("SELECT id FROM budget WHERE space_id = ? AND year = ? AND month = ?")
            .params(spaceId, year, month).query(UUID.class).single();
        jdbc.sql("DELETE FROM budget_line WHERE budget_id = ? AND space_id = ?").params(budgetId, spaceId).update();
        for (Line line : lines) {
            jdbc.sql("INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, ?)")
                .params(spaceId, budgetId, line.categoryId(), line.plannedAmount()).update();
        }
        return get(spaceId, year, month);
    }
}

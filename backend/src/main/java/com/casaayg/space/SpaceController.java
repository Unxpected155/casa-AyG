package com.casaayg.space;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.audit.AuditService;
import com.casaayg.shared.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/spaces")
class SpaceController {

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final AuditService audit;

    SpaceController(JdbcClient jdbc, Spaces spaces, AuditService audit) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.audit = audit;
    }

    record SpaceView(UUID id, String kind, String name, boolean archived, List<String> members) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<SpaceView> list() {
        // RLS solo devuelve los espacios donde soy miembro.
        return jdbc.sql("""
                SELECT s.id, s.kind, s.name, s.archived_at IS NOT NULL AS archived,
                       array_agg(u.display_name ORDER BY u.display_name) AS members
                  FROM space s
                  JOIN space_member m ON m.space_id = s.id
                  JOIN app_user u ON u.id = m.user_id
                 GROUP BY s.id ORDER BY s.archived_at NULLS FIRST, s.kind, s.created_at""")
            .query((rs, n) -> new SpaceView(rs.getObject("id", UUID.class), rs.getString("kind"),
                rs.getString("name"), rs.getBoolean("archived"),
                List.of((String[]) rs.getArray("members").getArray())))
            .list();
    }

    record Export(Instant exportedAt, SpaceView space, List<Map<String, Object>> categories,
                  List<Map<String, Object>> budgets, List<Map<String, Object>> transactions) {}

    /**
     * Exportación completa del espacio (ADR-005: si alguien sale del espacio familiar, cada quien
     * puede llevarse todo). Cualquier miembro puede exportar; queda auditado.
     */
    @GetMapping("/{spaceId}/export")
    @Transactional(readOnly = true)
    public ResponseEntity<Export> export(@PathVariable UUID spaceId, HttpServletRequest request) {
        spaces.requireMember(spaceId);
        SpaceView space = list().stream().filter(s -> s.id().equals(spaceId)).findFirst().orElseThrow();
        var categories = jdbc.sql("""
                SELECT id, name, category_group AS "group", active FROM category
                 WHERE space_id = ? ORDER BY category_group, name""").param(spaceId).query().listOfRows();
        var budgets = jdbc.sql("""
                SELECT b.year, b.month, l.category_id AS "categoryId", l.planned_amount AS "plannedAmount"
                  FROM budget b JOIN budget_line l ON l.budget_id = b.id
                 WHERE b.space_id = ? ORDER BY b.year, b.month""").param(spaceId).query().listOfRows();
        var transactions = jdbc.sql("""
                SELECT t.id, t.tx_date AS "date", t.description, t.amount, t.currency,
                       t.payment_method AS "paymentMethod", t.source, t.category_id AS "categoryId",
                       u.display_name AS "createdBy", t.origin_space_id IS NOT NULL AS "beforeMerge"
                  FROM transaction t JOIN app_user u ON u.id = t.created_by
                 WHERE t.space_id = ? ORDER BY t.tx_date, t.created_at""").param(spaceId).query().listOfRows();
        audit.record(CurrentUser.require(), "EXPORTACION", Map.of("espacio", spaceId.toString()), request);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"casa-ayg-" + LocalDate.now() + ".json\"")
            .body(new Export(Instant.now(), space, normalize(categories), normalize(budgets), normalize(transactions)));
    }

    /** Tipos de JDBC → JSON estable (fechas ISO, montos como número exacto). */
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        rows.forEach(row -> row.replaceAll((k, v) -> switch (v) {
            case java.sql.Date d -> d.toLocalDate().toString();
            case BigDecimal b -> b.toPlainString();
            case UUID u -> u.toString();
            case null, default -> v;
        }));
        return rows;
    }
}

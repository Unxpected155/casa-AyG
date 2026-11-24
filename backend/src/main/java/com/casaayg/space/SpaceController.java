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

    record Member(UUID id, String displayName) {}

    record SpaceView(UUID id, String kind, String name, boolean archived, List<Member> members) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<SpaceView> list() {
        // RLS solo devuelve los espacios donde soy miembro.
        record Row(UUID id, String kind, String name, boolean archived, UUID memberId, String memberName) {}
        List<Row> rows = jdbc.sql("""
                SELECT s.id, s.kind, s.name, s.archived_at IS NOT NULL AS archived,
                       u.id AS member_id, u.display_name AS member_name
                  FROM space s
                  JOIN space_member m ON m.space_id = s.id
                  JOIN app_user u ON u.id = m.user_id
                 ORDER BY s.archived_at NULLS FIRST, s.kind, s.created_at, u.display_name""")
            .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("name"),
                rs.getBoolean("archived"), rs.getObject("member_id", UUID.class), rs.getString("member_name")))
            .list();
        Map<UUID, SpaceView> spaces = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            spaces.computeIfAbsent(row.id(), id -> new SpaceView(id, row.kind(), row.name(), row.archived(),
                new java.util.ArrayList<>())).members().add(new Member(row.memberId(), row.memberName()));
        }
        return List.copyOf(spaces.values());
    }

    /** Versión del formato: sube si cambia la forma del archivo. */
    static final int EXPORT_FORMAT = 3;

    record Export(int format, Instant exportedAt, SpaceView space, List<Map<String, Object>> categories,
                  List<Map<String, Object>> budgets, List<Map<String, Object>> transactions,
                  List<Map<String, Object>> places, List<Map<String, Object>> transfers,
                  List<Map<String, Object>> goals, List<Map<String, Object>> goalParts,
                  List<Map<String, Object>> goalPlaces, List<Map<String, Object>> recurringPayments,
                  List<Map<String, Object>> debts) {}

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
                       u.display_name AS "createdBy", t.origin_space_id IS NOT NULL AS "beforeMerge",
                       t.place_id AS "placeId", t.gross_amount AS "grossAmount", t.tithe_of AS "titheOf",
                       t.recurring_id AS "recurringId", t.debt_id AS "debtId"
                  FROM transaction t JOIN app_user u ON u.id = t.created_by
                 WHERE t.space_id = ? ORDER BY t.tx_date, t.created_at""").param(spaceId).query().listOfRows();
        var places = jdbc.sql("""
                SELECT id, name, kind, currency, opening_balance AS "openingBalance", opening_date AS "openingDate",
                       parent_id AS "parentId", archived,
                       cdp_rate AS "cdpRate", cdp_term_days AS "cdpTermDays", cdp_day_basis AS "cdpDayBasis",
                       cdp_tax_pct AS "cdpTaxPct", cdp_matures_on AS "cdpMaturesOn",
                       cdp_payout_place AS "cdpPayoutPlace", cdp_last_contribution AS "cdpLastContribution"
                  FROM place WHERE space_id = ? ORDER BY created_at""").param(spaceId).query().listOfRows();
        var transfers = jdbc.sql("""
                SELECT x.id, x.tx_date AS "date", x.from_place AS "from", x.to_place AS "to", x.amount,
                       x.description, x.batch_id AS "batchId", u.display_name AS "createdBy"
                  FROM transfer x JOIN app_user u ON u.id = x.created_by
                 WHERE x.space_id = ? ORDER BY x.tx_date, x.created_at""").param(spaceId).query().listOfRows();
        var goals = jdbc.sql("""
                SELECT id, name, kind, target_amount AS "targetAmount", emergency_months AS "emergencyMonths",
                       currency, target_date AS "targetDate", priority, status, achieved_at AS "achievedAt"
                  FROM goal WHERE space_id = ? ORDER BY priority, created_at""").param(spaceId).query().listOfRows();
        var goalParts = jdbc.sql("""
                SELECT goal_id AS "goalId", name, amount, position FROM goal_part
                 WHERE space_id = ? ORDER BY goal_id, position""").param(spaceId).query().listOfRows();
        var goalPlaces = jdbc.sql("""
                SELECT goal_id AS "goalId", place_id AS "placeId" FROM goal_place
                 WHERE space_id = ? ORDER BY goal_id""").param(spaceId).query().listOfRows();
        var recurring = jdbc.sql("""
                SELECT id, name, category_id AS "categoryId", place_id AS "placeId", due_day AS "dueDay",
                       expected_amount AS "expectedAmount", currency, notify_days_before AS "notifyDaysBefore", active
                  FROM recurring_payment WHERE space_id = ? ORDER BY lower(name)""").param(spaceId).query().listOfRows();
        var debts = jdbc.sql("""
                SELECT id, kind, creditor, concept, amount, currency, borrowed_on AS "borrowedOn", due_date AS "dueDate"
                  FROM debt WHERE space_id = ? ORDER BY lower(creditor), borrowed_on""").param(spaceId).query().listOfRows();
        audit.record(CurrentUser.require(), "EXPORTACION", Map.of("espacio", spaceId.toString()), request);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"casa-ayg-" + LocalDate.now() + ".json\"")
            .body(new Export(EXPORT_FORMAT, Instant.now(), space, normalize(categories), normalize(budgets),
                normalize(transactions), normalize(places), normalize(transfers), normalize(goals),
                normalize(goalParts), normalize(goalPlaces), normalize(recurring), normalize(debts)));
    }

    /** Tipos de JDBC → JSON estable (fechas ISO, montos como número exacto). */
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        rows.forEach(row -> row.replaceAll((k, v) -> switch (v) {
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Timestamp t -> t.toInstant().toString();
            case java.time.OffsetDateTime t -> t.toInstant().toString();
            case BigDecimal b -> b.toPlainString();
            case UUID u -> u.toString();
            case null, default -> v;
        }));
        return rows;
    }
}

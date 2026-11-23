package com.casaayg.household;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.push.PushNotice;
import com.casaayg.space.Spaces;

import tools.jackson.databind.json.JsonMapper;

/**
 * Historial de cambios de la casa (V20): lo escribe un trigger en la BD; aquí solo se lee y se
 * avisa a quien había anotado algo que otra persona lo cambió o lo borró.
 */
@Service
public class ChangeLog {

    public record Change(Instant at, UUID actor, String actorName, UUID owner, String entity, String action,
                         Map<String, Object> before, Map<String, Object> after) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final ApplicationEventPublisher events;

    ChangeLog(JdbcClient jdbc, Spaces spaces, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Change> recent(UUID spaceId, int limit) {
        spaces.requireMember(spaceId);
        return jdbc.sql("""
                SELECT l.changed_at, l.actor, u.display_name, l.owner, l.entity, l.action, l.before::text, l.after::text
                  FROM change_log l JOIN app_user u ON u.id = l.actor
                 WHERE l.space_id = ?
                 ORDER BY l.changed_at DESC, l.id DESC
                 LIMIT ?""")
            .params(spaceId, Math.min(Math.max(limit, 1), 100))
            .query((rs, n) -> new Change(rs.getTimestamp(1).toInstant(), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), map(rs.getString(7)), map(rs.getString(8))))
            .list();
    }

    /**
     * Dentro de la transacción que editó o borró: si cambió algo que anotó OTRA persona, se le
     * avisa (después de confirmar; el aviso no lleva montos). now() es la hora de inicio de la
     * transacción: identifica las filas que esta misma transacción escribió.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void tellOwners(UUID spaceId) {
        record Row(UUID owner, String action, String description, String actor) {}
        List<Row> rows = jdbc.sql("""
                SELECT l.owner, l.action, l.before ->> 'descripcion', u.display_name
                  FROM change_log l JOIN app_user u ON u.id = l.actor
                 WHERE l.space_id = ? AND l.actor = current_app_user() AND l.changed_at = now()
                   AND l.owner IS NOT NULL AND l.owner <> l.actor""")
            .param(spaceId)
            .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
            .list();
        for (Row r : rows) {
            String verb = "BORRO".equals(r.action()) ? "borró" : "editó";
            String what = r.description() == null ? "un movimiento que anotaste"
                : "«" + shorten(r.description()) + "», que anotaste";
            events.publishEvent(new PushNotice(List.of(r.owner()), "La casa",
                r.actor() + " " + verb + " " + what + ".", "/casa"));
        }
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String json) {
        return json == null ? null : JSON.readValue(json, Map.class);
    }
}

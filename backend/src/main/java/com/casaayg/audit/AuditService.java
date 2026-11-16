package com.casaayg.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.casaayg.shared.db.AsUser;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Registro de eventos de seguridad (append-only, ver V2/V3). Nunca guarda secretos:
 * ni códigos, ni cookies, ni datos de passkeys.
 * Un fallo al auditar se registra en el log pero no rompe la operación del usuario.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AsUser asUser;
    private final JdbcClient jdbc;

    AuditService(AsUser asUser, JdbcClient jdbc) {
        this.asUser = asUser;
        this.jdbc = jdbc;
    }

    /** @param userId puede ser null (por ejemplo, un login fallido sin usuario conocido). */
    public void record(UUID userId, String eventType, Map<String, String> details, HttpServletRequest request) {
        List<String> flat = new ArrayList<>();
        details.forEach((k, v) -> {
            flat.add(k);
            flat.add(v == null ? "" : truncate(v));
        });
        String ip = request == null ? null : request.getRemoteAddr();
        try {
            asUser.run(userId, () -> jdbc.sql("""
                    INSERT INTO audit_log (user_id, event_type, details, ip)
                    VALUES (?, ?, jsonb_object(?::text[]), ?::inet)""")
                .params(userId, eventType, flat.toArray(String[]::new), ip)
                .update());
        } catch (RuntimeException e) {
            log.error("No se pudo auditar el evento {}: {}", eventType, e.getClass().getSimpleName());
        }
    }

    public void record(UUID userId, String eventType, HttpServletRequest request) {
        record(userId, eventType, Map.of(), request);
    }

    private static String truncate(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100);
    }
}

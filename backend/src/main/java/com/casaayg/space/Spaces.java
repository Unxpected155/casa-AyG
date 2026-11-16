package com.casaayg.space;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;

/**
 * Primera barrera contra IDOR: todo endpoint con /spaces/{spaceId} pasa por aquí.
 * Si no eres miembro del espacio, la respuesta es 404 (igual que si no existiera).
 * RLS es la segunda barrera: aunque esto fallara, la BD no devolvería las filas.
 *
 * Debe llamarse DENTRO de una transacción (para que la BD sepa quién eres).
 */
@Component
public class Spaces {

    private final JdbcClient jdbc;

    Spaces(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Puedo leerlo (soy miembro, aunque esté archivado). */
    public void requireMember(UUID spaceId) {
        load(spaceId);
    }

    /** Puedo escribir en él (soy miembro y no está archivado). */
    public void requireWritable(UUID spaceId) {
        if (load(spaceId)) {
            throw new ConflictException("El espacio está archivado: es de solo lectura.");
        }
    }

    /** @return si está archivado. */
    private boolean load(UUID spaceId) {
        // Con RLS, un espacio ajeno simplemente no aparece: el WHERE con el miembro es explícito
        // igual, para que la regla no dependa de una sola capa.
        return jdbc.sql("""
                SELECT s.archived_at IS NOT NULL FROM space s
                  JOIN space_member m ON m.space_id = s.id
                 WHERE s.id = ? AND m.user_id = current_app_user()""")
            .param(spaceId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(NotFoundException::new);
    }
}

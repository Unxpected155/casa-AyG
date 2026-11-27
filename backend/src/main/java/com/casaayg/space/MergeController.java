package com.casaayg.space;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.domain.Text.SpaceName;
import com.casaayg.push.PushKind;
import com.casaayg.push.PushNotice;
import com.casaayg.shared.security.RecentAuthentication;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Fusión de los espacios personales en el familiar (ADR-005). Toda la lógica y las reglas
 * viven en funciones de la BD (V5): aquí solo se exige la passkey reciente y se traducen errores.
 */
@RestController
@RequestMapping("/api/merge-requests")
class MergeController {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    MergeController(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    record MergeView(UUID id, String familyName, String status, boolean iAmRequester, String otherPerson,
                     Instant createdAt, Instant expiresAt, UUID familySpaceId) {}

    record ProposeRequest(@NotNull @Size(max = 30) String partnerUsername, @NotNull String familyName) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<MergeView> mine() {
        return jdbc.sql("SELECT * FROM merge_requests_mine()")
            .query((rs, n) -> new MergeView(rs.getObject("id", UUID.class), rs.getString("family_name"),
                rs.getString("status"), rs.getBoolean("i_am_requester"), rs.getString("other_display_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("family_space_id", UUID.class)))
            .list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, UUID> propose(@Valid @RequestBody ProposeRequest body) {
        RecentAuthentication.require();
        SpaceName name = new SpaceName(body.familyName());
        UUID id = translate(() -> jdbc.sql("SELECT merge_propose(?, ?)")
            .params(body.partnerUsername(), name.value()).query(UUID.class).single());
        tellOther(id, myName() + " propone unir sus finanzas en una casa. Confírmalo en Espacio familiar.", "/boda");
        return Map.of("id", id);
    }

    /** El segundo consentimiento: solo la pareja invitada, con su passkey reciente. */
    @PostMapping("/{id}/approve")
    @Transactional
    Map<String, UUID> approve(@PathVariable UUID id) {
        RecentAuthentication.require();
        UUID family = translate(() -> jdbc.sql("SELECT merge_approve(?)").param(id).query(UUID.class).optional()
            .orElse(null));
        if (family == null) {
            throw new ConflictException("La solicitud venció. Hay que proponerla de nuevo.");
        }
        tellOther(id, myName() + " aceptó: ya comparten la casa.", "/casa");
        return Map.of("familySpaceId", family);
    }

    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void decline(@PathVariable UUID id) {
        translate(() -> jdbc.sql("SELECT merge_decline(?)").param(id).query().singleValue());
    }

    /** Aviso a la otra persona de la propuesta, después de confirmar (ver PushNotice). */
    private void tellOther(UUID request, String text, String url) {
        jdbc.sql("SELECT merge_other_party(?)").param(request).query(UUID.class).optional()
            .ifPresent(other -> events.publishEvent(new PushNotice(PushKind.CASA, List.of(other), "Espacio familiar", text, url)));
    }

    private String myName() {
        return jdbc.sql("SELECT display_name FROM app_user WHERE id = current_app_user()").query(String.class).single();
    }

    private static <T> T translate(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            String state = sqlState(e);
            if ("P0002".equals(state)) {
                throw new NotFoundException();
            }
            if ("55000".equals(state)) {
                throw new ConflictException("No se puede: ya hay un espacio familiar o una solicitud pendiente.");
            }
            if ("23505".equals(state)) {
                throw new ConflictException("Hay una misma factura registrada en ambos espacios. Borra una y reintenta.");
            }
            throw e;
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}

package com.casaayg.me;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.audit.AuditService;
import com.casaayg.shared.security.AuthCodes;
import com.casaayg.shared.security.CasaSecurityProperties;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.security.RecentAuthentication;
import com.casaayg.shared.security.SessionRevoker;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/me")
class MeController {

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final SessionRevoker sessions;
    private final CasaSecurityProperties security;

    MeController(JdbcClient jdbc, AuditService audit, SessionRevoker sessions, CasaSecurityProperties security) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.sessions = sessions;
        this.security = security;
    }

    /** {@code theme}: AUTO (sigue al teléfono), CLARO u OSCURO. */
    record Me(UUID id, String username, String displayName, int passkeys, int recoveryCodesLeft, String theme) {}

    enum Theme { AUTO, CLARO, OSCURO }

    record ThemeRequest(@jakarta.validation.constraints.NotNull Theme theme) {}

    record Passkey(String id, String label, Instant created, Instant lastUsed) {}

    @GetMapping
    @Transactional(readOnly = true)
    Me me() {
        UUID me = CurrentUser.require();
        return jdbc.sql("""
                SELECT u.id, u.username, u.display_name,
                       (SELECT count(*) FROM user_credentials c WHERE c.user_entity_user_id = user_handle(u.id))::int,
                       auth_recovery_codes_left(), u.theme
                  FROM app_user u WHERE u.id = ?""")
            .param(me)
            .query((rs, n) -> new Me(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                rs.getString(6)))
            .single();
    }

    /** El tema elegido en Perfil; vale en todos los dispositivos de la persona. */
    @PutMapping("/theme")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void theme(@jakarta.validation.Valid @RequestBody ThemeRequest body) {
        jdbc.sql("UPDATE app_user SET theme = ? WHERE id = ?").params(body.theme().name(), CurrentUser.require()).update();
    }

    @GetMapping("/passkeys")
    @Transactional(readOnly = true)
    List<Passkey> passkeys() {
        // user_credentials se puede leer antes del login (lo necesita Spring), así que aquí
        // el filtro por dueño es OBLIGATORIO en la consulta.
        return jdbc.sql("""
                SELECT credential_id, label, created, last_used FROM user_credentials
                 WHERE user_entity_user_id = user_handle(?) ORDER BY created""")
            .param(CurrentUser.require())
            .query((rs, n) -> new Passkey(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3)),
                instant(rs.getTimestamp(4))))
            .list();
    }

    /**
     * Lo que el navegador necesita para decirle al gestor de contraseñas qué passkeys siguen
     * valiendo (Signal API): así una passkey borrada aquí deja de aparecer en el teléfono.
     * El rpId sale del servidor (el mismo con que se registraron), nunca del hostname.
     * IDs en base64url sin relleno, que es como ya se guardan.
     */
    record AcceptedPasskeys(String rpId, String userId, List<String> credentialIds) {}

    @GetMapping("/passkeys/accepted")
    @Transactional(readOnly = true)
    AcceptedPasskeys acceptedPasskeys() {
        UUID me = CurrentUser.require();
        String handle = jdbc.sql("SELECT user_handle(?)").param(me).query(String.class).single();
        List<String> ids = jdbc.sql("SELECT credential_id FROM user_credentials WHERE user_entity_user_id = ?")
            .param(handle).query(String.class).list();
        return new AcceptedPasskeys(security.rpId(), handle, ids);
    }

    @DeleteMapping("/passkeys/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deletePasskey(@PathVariable String credentialId, HttpServletRequest request) {
        RecentAuthentication.require();
        UUID me = CurrentUser.require();
        int count = jdbc.sql("SELECT count(*) FROM user_credentials WHERE user_entity_user_id = user_handle(?)")
            .param(me).query(Integer.class).single();
        int deleted = jdbc.sql("DELETE FROM user_credentials WHERE credential_id = ? AND user_entity_user_id = user_handle(?)")
            .params(credentialId, me).update();
        if (deleted == 0) {
            throw new NotFoundException();
        }
        if (count <= 1) {
            throw new ConflictException("No puedes borrar tu última passkey: te quedarías sin acceso.");
        }
        audit.record(me, "PASSKEY_BORRADA", request);
    }

    /** 10 códigos nuevos (los anteriores dejan de servir). Se muestran UNA sola vez. */
    @PostMapping("/recovery-codes")
    @Transactional
    Map<String, List<String>> newRecoveryCodes(HttpServletRequest request) {
        RecentAuthentication.require();
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = AuthCodes.generate();
            codes.add(AuthCodes.format(code));
            hashes.add(HexFormat.of().formatHex(AuthCodes.hash(code)));
        }
        jdbc.sql("SELECT auth_replace_recovery_codes(ARRAY(SELECT decode(h, 'hex') FROM unnest(?::text[]) AS h))")
            .param(hashes.toArray(String[]::new)).query().singleValue();
        audit.record(CurrentUser.require(), "CODIGOS_RECUPERACION_NUEVOS", request);
        return Map.of("codes", codes);
    }

    @PostMapping("/sessions/revoke-others")
    Map<String, Integer> revokeOtherSessions(HttpServletRequest request) {
        UUID me = CurrentUser.require();
        int revoked = sessions.revokeOthers(me, request.getSession().getId());
        audit.record(me, "SESIONES_CERRADAS", Map.of("cantidad", String.valueOf(revoked)), request);
        return Map.of("revoked", revoked);
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

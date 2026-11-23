package com.casaayg.invoice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.shared.web.ConflictException;

import jakarta.servlet.http.HttpSession;

/**
 * Conectar y desconectar el buzón de facturas. OAuth 2.0 con código + PKCE (RFC 7636/9700):
 * el {@code state} y el verificador viven en la sesión de quien conecta, sirven una sola vez y
 * vencen en 10 minutos. El refresh token se guarda cifrado (TokenCipher).
 */
@Service
public class MailboxService {

    private static final Logger log = LoggerFactory.getLogger(MailboxService.class);
    private static final String SESSION_KEY = "casaayg.mailbox.oauth";
    private static final long TTL_SECONDS = 600;

    record Pending(String state, String verifier, Instant expires) implements java.io.Serializable {}

    public record Status(boolean available, boolean connected, String email, Instant connectedAt,
                         Instant lastCheckAt, String lastError, List<Count> lastMonth) {}

    public record Count(String result, int count) {}

    private final JdbcClient jdbc;
    private final GmailApi gmail;
    private final TokenCipher cipher;
    private final SecureRandom random = new SecureRandom();

    MailboxService(JdbcClient jdbc, GmailApi gmail, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.gmail = gmail;
        this.cipher = cipher;
    }

    public boolean available() {
        return gmail.configured() && cipher.configured();
    }

    @Transactional(readOnly = true)
    public Status status() {
        record Row(String email, Instant connectedAt, Instant lastCheckAt, String lastError) {}
        Row row = jdbc.sql("SELECT email, connected_at, last_check_at, last_error FROM mailbox WHERE id = 1")
            .query((rs, n) -> new Row(rs.getString(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)),
                rs.getString(4)))
            .optional().orElse(null);
        List<Count> counts = jdbc.sql("""
                SELECT result, count(*)::int FROM mailbox_message
                 WHERE processed_at > now() - interval '30 days' GROUP BY result ORDER BY result""")
            .query((rs, n) -> new Count(rs.getString(1), rs.getInt(2))).list();
        return row == null
            ? new Status(available(), false, null, null, null, null, counts)
            : new Status(available(), true, row.email(), row.connectedAt(), row.lastCheckAt(), row.lastError(), counts);
    }

    /** Paso 1: la URL de Google, con un state y un desafío PKCE nuevos guardados en la sesión. */
    public String start(HttpSession session) {
        if (!available()) {
            throw new ConflictException("El buzón todavía no está configurado en el servidor.");
        }
        String state = randomToken();
        String verifier = randomToken() + randomToken();
        session.setAttribute(SESSION_KEY, new Pending(state, verifier, Instant.now().plusSeconds(TTL_SECONDS)));
        return gmail.authorizationUrl(state, challenge(verifier));
    }

    /** Paso 2: Google volvió con un código. Solo vale con el state de ESTA sesión, una vez. */
    @Transactional
    public String finish(HttpSession session, String code, String state, UUID by) {
        Object saved = session.getAttribute(SESSION_KEY);
        session.removeAttribute(SESSION_KEY);
        if (!(saved instanceof Pending p) || Instant.now().isAfter(p.expires())
                || !MessageDigest.isEqual(p.state().getBytes(StandardCharsets.US_ASCII),
                    state.getBytes(StandardCharsets.US_ASCII))) {
            throw new ConflictException("La conexión venció o no es válida. Vuelve a intentarlo.");
        }
        String refresh;
        String email;
        try {
            refresh = gmail.exchangeCode(code, p.verifier());
            email = gmail.emailAddress(gmail.accessToken(refresh));
        } catch (RuntimeException e) {
            log.warn("No se pudo conectar el buzón: {}", e.getClass().getSimpleName());
            throw new ConflictException("Google no aceptó la conexión. Revisa que diste el permiso de solo lectura.");
        }
        if (email == null || email.length() > 254) {
            throw new ConflictException("Google no devolvió el correo del buzón.");
        }
        String previous = currentToken();
        jdbc.sql("""
                INSERT INTO mailbox (id, email, refresh_token_enc, connected_by) VALUES (1, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, refresh_token_enc = EXCLUDED.refresh_token_enc,
                    connected_by = EXCLUDED.connected_by, connected_at = now(), last_error = NULL""")
            .params(email, cipher.encrypt(refresh), by).update();
        if (previous != null && !previous.equals(refresh)) {
            revokeQuietly(previous);
        }
        return email;
    }

    /** Desconectar: se borra el token y se revoca en Google. */
    @Transactional
    public void disconnect() {
        String token = currentToken();
        jdbc.sql("DELETE FROM mailbox WHERE id = 1").update();
        if (token != null) {
            revokeQuietly(token);
        }
    }

    String currentToken() {
        return jdbc.sql("SELECT refresh_token_enc FROM mailbox WHERE id = 1").query(byte[].class).optional()
            .map(cipher::decrypt).orElse(null);
    }

    private void revokeQuietly(String token) {
        try {
            gmail.revoke(token);
        } catch (RuntimeException e) {
            // Ya no se guarda: aunque Google no responda, la app no lo puede usar.
            log.warn("No se pudo revocar el permiso en Google: {}", e.getClass().getSimpleName());
        }
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

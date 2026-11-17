package com.casaayg.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Envía un mensaje cifrado al servicio de push de un dispositivo, firmado con la llave VAPID
 * del servidor (RFC 8292). La llave vive en los secretos (vapid_private_key / vapid_public_key,
 * DER en base64). Sin llave configurada, en desarrollo se usa una temporal y en producción las
 * notificaciones quedan apagadas.
 *
 * SSRF: la URL la manda el navegador, así que solo se aceptan los servicios de push conocidos
 * (y solo HTTPS, sin seguir redirecciones).
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    /** Servicios de push de los navegadores (Apple, Google/Chrome/Android, Mozilla, Microsoft). */
    static final List<String> ALLOWED_HOSTS = List.of(
        "push.apple.com", "fcm.googleapis.com", "push.services.mozilla.com", "notify.windows.com");

    public enum Result { OK, GONE, ERROR }

    private final KeyPair vapid;
    private final String publicKey;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    WebPushSender(@Value("${vapid_private_key:}") String privateDer,
                  @Value("${vapid_public_key:}") String publicDer,
                  @Value("${casaayg.push.subject:}") String subject,
                  @Value("${spring.profiles.active:}") String profiles) throws GeneralSecurityException {
        boolean prod = profiles.contains("prod");
        if (!privateDer.isBlank() && !publicDer.isBlank()) {
            KeyFactory ec = KeyFactory.getInstance("EC");
            this.vapid = new KeyPair(
                ec.generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicDer.strip()))),
                ec.generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(privateDer.strip()))));
        } else if (!prod) {
            log.warn("Sin llave VAPID: se usa una temporal (solo desarrollo; las suscripciones no sobreviven un reinicio).");
            this.vapid = WebPushCrypto.newKeyPair();
        } else {
            log.warn("Sin llave VAPID: las notificaciones push están apagadas.");
            this.vapid = null;
        }
        this.publicKey = vapid == null ? null : WebPushCrypto.encode(WebPushCrypto.uncompressed((ECPublicKey) vapid.getPublic()));
        this.subject = subject.isBlank() ? "https://localhost" : subject;
    }

    public boolean enabled() {
        return vapid != null;
    }

    /** La llave pública que el navegador necesita para suscribirse (applicationServerKey). */
    public String publicKey() {
        return publicKey;
    }

    /** Solo HTTPS y solo servicios de push conocidos. */
    public static boolean allowedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && ALLOWED_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Result send(String endpoint, String p256dh, String auth, String json) {
        if (!enabled() || !allowedEndpoint(endpoint)) {
            return Result.ERROR;
        }
        try {
            byte[] body = WebPushCrypto.encrypt(json.getBytes(StandardCharsets.UTF_8),
                WebPushCrypto.decode(p256dh), WebPushCrypto.decode(auth));
            URI uri = URI.create(endpoint);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "vapid t=" + jwt(uri) + ", k=" + publicKey)
                .header("TTL", "86400")
                .header("Urgency", "normal")
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return Result.OK;
            }
            if (status == 404 || status == 410) {
                return Result.GONE;
            }
            log.warn("El servicio de push respondió {} ({})", status, uri.getHost());
            return Result.ERROR;
        } catch (GeneralSecurityException | java.io.IOException e) {
            log.warn("No se pudo enviar una notificación: {}", e.getClass().getSimpleName());
            return Result.ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.ERROR;
        }
    }

    /** JWT ES256 para el origen del servicio de push, válido 12 horas. */
    String jwt(URI endpoint) throws GeneralSecurityException {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost();
        String header = WebPushCrypto.encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = WebPushCrypto.encode(("{\"aud\":\"" + audience + "\",\"exp\":"
            + Instant.now().plus(Duration.ofHours(12)).getEpochSecond() + ",\"sub\":\"" + subject + "\"}")
            .getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign((ECPrivateKey) vapid.getPrivate());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + WebPushCrypto.encode(signer.sign());
    }
}

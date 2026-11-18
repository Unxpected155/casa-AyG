package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** V8: suscripciones de push. Cada quien ve y borra solo las suyas; el servidor solo le habla a servicios de push. */
class PushSubscriptionTest extends WebTestSupport {

    TestData.AppUser a;
    TestData.AppUser b;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
        }
    }

    @Test
    void suscribirseContarYDesuscribirseSoloLoPropio() throws Exception {
        String endpoint = "https://fcm.googleapis.com/fcm/send/prueba-" + UUID.randomUUID();
        as(a, post("/api/push/subscriptions").content(subscription(endpoint))).andExpect(status().isNoContent());
        // Suscribirse otra vez desde el mismo dispositivo no duplica.
        as(a, post("/api/push/subscriptions").content(subscription(endpoint))).andExpect(status().isNoContent());
        assertThat(devices(a)).isEqualTo(1);
        assertThat(devices(b)).isZero();

        // B no puede borrar la suscripción de A (y tampoco puede "robarla" para sí).
        as(b, delete("/api/push/subscriptions").content(toJson(Map.of("endpoint", endpoint)))).andExpect(status().isNoContent());
        assertThat(devices(a)).isEqualTo(1);
        as(b, post("/api/push/subscriptions").content(subscription(endpoint))).andExpect(status().isConflict());

        // RLS: aunque la app olvidara filtrar, B no ve las filas de A.
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, b.id());
            try (var rs = app.createStatement().executeQuery("SELECT count(*) FROM casaayg.push_subscription")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }

        as(a, delete("/api/push/subscriptions").content(toJson(Map.of("endpoint", endpoint)))).andExpect(status().isNoContent());
        assertThat(devices(a)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://fcm.googleapis.com/fcm/send/x",          // sin HTTPS
        "https://evil.example.com/fcm.googleapis.com/x",  // otro servidor
        "https://fcm.googleapis.com.evil.example/x",     // sufijo falso
        "https://169.254.169.254/latest/meta-data",      // metadatos de la nube (SSRF)
        "https://user@fcm.googleapis.com/x",             // credenciales en la URL
        "https://fcm.googleapis.com:8443/x"})            // otro puerto
    void soloSeAceptanServiciosDePushConocidos(String endpoint) throws Exception {
        as(a, post("/api/push/subscriptions").content(subscription(endpoint))).andExpect(status().isBadRequest());
    }

    @Test
    void llavesInvalidasSeRechazan() throws Exception {
        String body = toJson(Map.of("endpoint", "https://web.push.apple.com/abc",
            "keys", Map.of("p256dh", "B".repeat(87), "auth", "AAAAAAAAAAAAAAAAAAAAAA")));
        as(a, post("/api/push/subscriptions").content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void elAvisoDiarioEncuentraSoloAQuienTieneUnCdpVencido() throws Exception {
        UUID bank = place(a, "BCR", "BANCO");
        UUID cdp = place(a, "CDP", "CDP");
        as(a, put("/api/spaces/" + a.personalSpace() + "/places/" + cdp + "/cdp").content(toJson(Map.of(
                "rate", "2.85", "termDays", 30, "dayBasis", 360, "taxPct", "15",
                "maturesOn", LocalDate.now().toString(), "payoutPlace", bank.toString()))))
            .andExpect(status().isOk());
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT user_id FROM casaayg.cdp_due_users(?)")) {
                ps.setObject(1, LocalDate.now());
                // La BD es compartida entre pruebas: otras pueden dejar CDP vencidos. Lo que importa
                // es que A esté y B (sin CDP) no.
                java.util.List<UUID> users = new java.util.ArrayList<>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        users.add(rs.getObject(1, UUID.class));
                    }
                }
                assertThat(users).contains(a.id()).doesNotContain(b.id());
            }
        }
    }

    @Test
    void elServiceWorkerNoPideSesion() throws Exception {
        // En CI el frontend no está compilado (404); lo que importa es que la seguridad no lo bloquee.
        int code = mvc.perform(get("/sw.js")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(200, 404);
    }

    // --- utilidades ----------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private int devices(TestData.AppUser user) throws Exception {
        String body = as(user, get("/api/push")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) json(body).get("devices")).intValue();
    }

    private UUID place(TestData.AppUser user, String name, String kind) throws Exception {
        String body = as(user, post("/api/spaces/" + user.personalSpace() + "/places").content(toJson(Map.of(
                "name", name, "kind", kind, "currency", "CRC", "openingBalance", "1000",
                "openingDate", LocalDate.now().toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    /** Una suscripción con llaves reales, como la arma el navegador. */
    private static String subscription(String endpoint) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = gen.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        byte[] raw = new byte[65];
        raw[0] = 4;
        byte[] x = pub.getW().getAffineX().toByteArray();
        byte[] y = pub.getW().getAffineY().toByteArray();
        System.arraycopy(x, Math.max(0, x.length - 32), raw, 1 + 32 - Math.min(32, x.length), Math.min(32, x.length));
        System.arraycopy(y, Math.max(0, y.length - 32), raw, 33 + 32 - Math.min(32, y.length), Math.min(32, y.length));
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        return toJson(Map.of("endpoint", endpoint, "expirationTime", 0,
            "keys", Map.of("p256dh", b64.encodeToString(raw), "auth", b64.encodeToString(auth))));
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.casaayg.invoice.GmailApi;
import com.casaayg.invoice.InvoiceSamples;
import com.casaayg.invoice.InvoiceVerifier;
import com.casaayg.invoice.TestPki;
import com.casaayg.push.PushNotice;

/**
 * Parte 3 de la fase 8: conectar el buzón (OAuth + PKCE) y leerlo. Gmail es falso; las facturas,
 * sintéticas y firmadas con autoridades de prueba.
 */
@RecordApplicationEvents
@Import(MailboxTest.Fakes.class)
@org.springframework.test.context.TestPropertySource(properties = "casaayg.invoices.check-cooldown=0s")
// Este contexto (con Gmail falso) tiene su propio pool: se cierra al terminar para no pasar el
// límite de conexiones del rol de la app en la BD de pruebas.
@org.springframework.test.annotation.DirtiesContext
class MailboxTest extends WebTestSupport {

    static final String ANA_CEDULA = "199999999";

    /** Gmail falso: correos en memoria y un único código de autorización válido. */
    static class FakeGmail implements GmailApi {
        final Map<String, List<Attachment>> messages = new LinkedHashMap<>();
        final List<String> revoked = new ArrayList<>();
        String challenge;

        @Override public boolean configured() { return true; }

        @Override public String authorizationUrl(String state, String codeChallenge) {
            challenge = codeChallenge;
            return "https://accounts.example/auth?state=" + state;
        }

        @Override public String exchangeCode(String code, String verifier) {
            if (!code.equals("codigo-de-google-123") || !challenge.equals(s256(verifier))) {
                throw new IllegalStateException("código o PKCE inválido");
            }
            return "1//refresh-de-prueba";
        }

        @Override public String accessToken(String refreshToken) {
            if (!refreshToken.equals("1//refresh-de-prueba")) {
                throw new IllegalStateException("revocado");
            }
            return "access";
        }

        @Override public String emailAddress(String accessToken) { return "facturas.casa@example.com"; }

        @Override public List<String> listMessages(String accessToken, String query, int max) {
            List<String> ids = new ArrayList<>(messages.keySet());
            java.util.Collections.reverse(ids); // los más nuevos primero, como Gmail
            return ids;
        }

        @Override public List<Attachment> xmlAttachments(String accessToken, String id) {
            return messages.getOrDefault(id, List.of());
        }

        @Override public void revoke(String token) { revoked.add(token); }

        static String s256(String v) {
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static TestPki.Signer comercio;
    static TestPki.Signer hacienda;
    static TestPki.Signer comercioDesconocido;

    @TestConfiguration
    static class Fakes {
        @Bean @Primary
        FakeGmail fakeGmail() {
            return new FakeGmail();
        }

        @Bean @Primary
        InvoiceVerifier testInvoiceVerifier() throws Exception {
            TestPki pki = new TestPki();
            TestPki.Signer ca = pki.authority("raiz");
            comercio = pki.issue(ca, "raiz", "comercio", "CPJ-" + InvoiceSamples.EMISOR, "digitalSignature");
            hacienda = pki.issue(ca, "raiz", "hacienda", "CPJ-" + InvoiceSamples.HACIENDA, "digitalSignature");
            TestPki.Signer otra = pki.authority("otra");
            comercioDesconocido = pki.issue(otra, "otra", "comerciodesconocido", "CPJ-" + InvoiceSamples.EMISOR,
                "digitalSignature");
            return new InvoiceVerifier(List.of(ca.cert()), InvoiceSamples.HACIENDA, Clock.systemUTC());
        }
    }

    @Autowired FakeGmail gmail;
    @Autowired ApplicationEvents events;

    TestData.AppUser ana;
    int seq = (int) (System.nanoTime() % 1_000_000);

    @BeforeEach
    void seed() throws Exception {
        gmail.messages.clear();
        gmail.revoked.clear();
        try (Connection su = asSuperuser()) {
            su.createStatement().execute("DELETE FROM mailbox; DELETE FROM mailbox_message; DELETE FROM tax_id");
            ana = TestData.adminCreateUser(su, "Ana");
        }
        as(ana, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/me/tax-id")
            .content(toJson(Map.of("number", ANA_CEDULA)))).andExpect(status().isNoContent());
    }

    // --- conectar --------------------------------------------------------------------------------

    @Test
    void conectarPidePasskeyRecienteYUsaStatePkceDeUnSoloUso() throws Exception {
        MockHttpSession session = new MockHttpSession();
        as(ana, post("/api/invoices/mailbox/connect").session(session), Duration.ofMinutes(10))
            .andExpect(status().isForbidden());

        String state = connect(session);
        // Un state que no es el de esta sesión no sirve.
        as(ana, post("/api/invoices/mailbox/callback").session(session)
                .content(toJson(Map.of("code", "codigo-de-google-123", "state", "x".repeat(43)))))
            .andExpect(status().isConflict());
        // Y el intento fallido gastó el state: hay que volver a empezar.
        as(ana, post("/api/invoices/mailbox/callback").session(session)
                .content(toJson(Map.of("code", "codigo-de-google-123", "state", state))))
            .andExpect(status().isConflict());

        state = connect(session);
        as(ana, post("/api/invoices/mailbox/callback").session(session)
                .content(toJson(Map.of("code", "codigo-de-google-123", "state", state))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("facturas.casa@example.com"));
        as(ana, get("/api/invoices/mailbox")).andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.email").value("facturas.casa@example.com"));

        // El token no queda legible en la BD.
        try (Connection su = asSuperuser(); var rs = su.createStatement().executeQuery(
                "SELECT encode(refresh_token_enc, 'escape') FROM mailbox")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain("refresh-de-prueba");
        }
    }

    @Test
    void desconectarPidePasskeyRecienteYRevocaEnGoogle() throws Exception {
        connectFully();
        as(ana, delete("/api/invoices/mailbox"), Duration.ofMinutes(10)).andExpect(status().isForbidden());
        as(ana, delete("/api/invoices/mailbox")).andExpect(status().isNoContent());
        assertThat(gmail.revoked).containsExactly("1//refresh-de-prueba");
        as(ana, get("/api/invoices/mailbox")).andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void laVueltaDeGoogleSoloPasaElCodigoALaPantalla() throws Exception {
        mvc.perform(get("/api/invoices/oauth/callback").param("code", "codigo-de-google-123").param("state", "s".repeat(43)))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/facturas?code=codigo-de-google-123&state=" + "s".repeat(43)));
        mvc.perform(get("/api/invoices/oauth/callback").param("error", "access_denied"))
            .andExpect(status().isFound()).andExpect(header().string("Location", "/facturas?buzon=cancelado"));
        mvc.perform(get("/api/invoices/oauth/callback").param("code", "<script>").param("state", "s".repeat(43)))
            .andExpect(header().string("Location", "/facturas?buzon=cancelado"));
    }

    // --- leer -------------------------------------------------------------------------------------

    @Test
    void elLectorJuntaVerificaYDejaPorRevisarSoloLoDeLaCasa() throws Exception {
        connectFully();
        int a = ++seq, b = ++seq, c = ++seq, d = ++seq;
        message("m1", attach(TestPki.sign(InvoiceSamples.factura(a, ANA_CEDULA, "11300.00"), comercio)),
            attach(TestPki.sign(InvoiceSamples.mensaje(a, ANA_CEDULA, "11300.00"), hacienda)));
        message("m2", attach(TestPki.sign(InvoiceSamples.factura(a, ANA_CEDULA, "11300.00"), comercio)),
            attach(TestPki.sign(InvoiceSamples.mensaje(a, ANA_CEDULA, "11300.00"), hacienda)));
        // La factura y la respuesta de Hacienda en correos distintos.
        message("m3", attach(TestPki.sign(InvoiceSamples.factura(b, ANA_CEDULA, "500.00"), comercio)));
        message("m4", attach(TestPki.sign(InvoiceSamples.mensaje(b, ANA_CEDULA, "500.00"), hacienda)));
        // De alguien que no es de la casa.
        message("m5", attach(TestPki.sign(InvoiceSamples.factura(c, "188888888", "9.00"), comercio)),
            attach(TestPki.sign(InvoiceSamples.mensaje(c, "188888888", "9.00"), hacienda)));
        // Alterada después de firmar.
        message("m6", attach(new String(TestPki.sign(InvoiceSamples.factura(d, ANA_CEDULA, "100.00"), comercio),
                StandardCharsets.UTF_8).replace("100.00", "1.00").getBytes(StandardCharsets.UTF_8)),
            attach(TestPki.sign(InvoiceSamples.mensaje(d, ANA_CEDULA, "1.00"), hacienda)));
        message("m7", new GmailApi.Attachment("foto.xml", "<html/>".getBytes(StandardCharsets.UTF_8)));

        as(ana, post("/api/invoices/mailbox/check")).andExpect(status().isOk())
            .andExpect(jsonPath("$.read").value(7)).andExpect(jsonPath("$.added").value(2));

        // m2 es la misma factura que m1: Gmail da primero el más nuevo, así que m1 es la repetida.
        assertThat(results()).containsEntry("m2", "NUEVA").containsEntry("m1", "REPETIDA")
            .containsEntry("m5", "AJENA").containsEntry("m6", "RECHAZADA").containsEntry("m7", "SIN_XML");
        // m4 (la respuesta) llegó primero sola; m3 (la factura) la encontró y la completó.
        assertThat(results().get("m4")).isEqualTo("INCOMPLETA");
        assertThat(results().get("m3")).isEqualTo("NUEVA");
        as(ana, get("/api/invoices")).andExpect(jsonPath("$.length()").value(2));
        // No sale un aviso por factura: van en el resumen de las 6 p. m. (dos nuevas para Ana).
        assertThat(events.stream(PushNotice.class).filter(n -> n.to().contains(ana.id()))).isEmpty();
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT invoices FROM casaayg.invoice_digest(now() - interval '1 hour') WHERE user_id = ?")) {
                ps.setObject(1, ana.id());
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(2);
                }
            }
        }

        // Lo ya leído no se vuelve a procesar.
        as(ana, post("/api/invoices/mailbox/check")).andExpect(jsonPath("$.read").value(0));
    }

    @Test
    void unaFirmaDeOtraAutoridadLlegaPorRevisarSinLaMarcaDeVerificada() throws Exception {
        connectFully();
        int n = ++seq;
        message("m1", attach(TestPki.sign(InvoiceSamples.factura(n, ANA_CEDULA, "2500.00"), comercioDesconocido)),
            attach(TestPki.sign(InvoiceSamples.mensaje(n, ANA_CEDULA, "2500.00"), hacienda)));
        as(ana, post("/api/invoices/mailbox/check")).andExpect(jsonPath("$.added").value(1));
        as(ana, get("/api/invoices")).andExpect(jsonPath("$[0].verified").value(false));
        try (Connection su = asSuperuser(); var rs = su.createStatement().executeQuery(
                "SELECT result, detail FROM mailbox_message WHERE message_id = 'm1'")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("NUEVA");
            assertThat(rs.getString(2)).contains("CN=CA otra").doesNotContain(ANA_CEDULA);
        }
    }

    @Test
    void conElPermisoRevocadoAvisaQueHayQueReconectar() throws Exception {
        try (Connection su = asSuperuser(); var ps = su.prepareStatement(
                "INSERT INTO mailbox (id, email, refresh_token_enc, connected_by) VALUES (1, 'x@example.com', ?, ?)")) {
            ps.setBytes(1, com.casaayg.invoice.TokenCipherTestAccess.encrypt(GMAIL_TOKEN_KEY, "revocado"));
            ps.setObject(2, ana.id());
            ps.executeUpdate();
        }
        as(ana, post("/api/invoices/mailbox/check")).andExpect(jsonPath("$.read").value(0));
        as(ana, get("/api/invoices/mailbox")).andExpect(jsonPath("$.lastError").value(
            "Google no aceptó el permiso: vuelve a conectar el buzón."));
    }

    // --- utilidades -------------------------------------------------------------------------------

    private String connect(MockHttpSession session) throws Exception {
        String url = String.valueOf(json(as(ana, post("/api/invoices/mailbox/connect").session(session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("url"));
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
    }

    private void connectFully() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = connect(session);
        as(ana, post("/api/invoices/mailbox/callback").session(session)
                .content(toJson(Map.of("code", "codigo-de-google-123", "state", state))))
            .andExpect(status().isOk());
    }

    private void message(String id, GmailApi.Attachment... attachments) {
        gmail.messages.put(id, List.of(attachments));
    }

    private static GmailApi.Attachment attach(byte[] xml) {
        return new GmailApi.Attachment("comprobante.xml", xml);
    }

    private Map<String, String> results() throws Exception {
        Map<String, String> out = new HashMap<>();
        try (Connection su = asSuperuser(); var rs = su.createStatement().executeQuery(
                "SELECT message_id, result FROM mailbox_message")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, Duration.ZERO);
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request, Duration ago) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user, ago))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

/**
 * Fase 3: login con passkey, códigos de inscripción/recuperación, sesiones y CSRF.
 * El registro y el login usan un autenticador WebAuthn EMULADO (firmas reales).
 */
class AuthenticationTest extends WebTestSupport {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
    private static final java.util.Random RANDOM = new java.util.Random();

    TestData.AppUser user;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.security.core.session.SessionRegistry sessionRegistry;

    @BeforeEach
    void createUser() throws SQLException {
        try (Connection su = asSuperuser()) {
            user = TestData.adminCreateUser(su, "Persona de prueba");
        }
    }

    // --- Sin sesión ---------------------------------------------------------------------

    @Test
    void laApiSinSesionResponde401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/spaces")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrarPasskeySinSesionResponde401() throws Exception {
        mvc.perform(post("/webauthn/register/options").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void unPostSinTokenCsrfResponde403() throws Exception {
        mvc.perform(post("/api/me/sessions/revoke-others").with(authentication(passkeyLogin(user))))
            .andExpect(status().isForbidden());
    }

    @Test
    void rutasDesconocidasEstanCerradas() throws Exception {
        mvc.perform(get("/cualquier-cosa").with(authentication(passkeyLogin(user)))).andExpect(status().isForbidden());
    }

    // --- Códigos ------------------------------------------------------------------------

    @Test
    void elCodigoDeInscripcionAbreUnaSesionQueSoloRegistraPasskeys() throws Exception {
        MockHttpSession session = new MockHttpSession();
        codeLogin(session, user.username(), user.enrollmentCode()).andExpect(status().isOk());

        // Puede pedir opciones de registro...
        mvc.perform(post("/webauthn/register/options").session(session).with(csrf())).andExpect(status().isOk());
        // ...pero NO puede leer datos.
        mvc.perform(get("/api/me").session(session)).andExpect(status().isForbidden());
        assertThat(auditEvents()).contains("USUARIO_CREADO", "LOGIN_INSCRIPCION");
    }

    @Test
    void unCodigoNoSePuedeUsarDosVeces() throws Exception {
        codeLogin(new MockHttpSession(), user.username(), user.enrollmentCode()).andExpect(status().isOk());
        codeLogin(new MockHttpSession(), user.username(), user.enrollmentCode()).andExpect(status().isUnauthorized());
    }

    @Test
    void unCodigoDeOtroUsuarioNoSirve() throws Exception {
        TestData.AppUser other;
        try (Connection su = asSuperuser()) {
            other = TestData.adminCreateUser(su, "Otra persona");
        }
        codeLogin(new MockHttpSession(), user.username(), other.enrollmentCode()).andExpect(status().isUnauthorized());
    }

    @Test
    void trasCincoFallosElUsuarioQuedaBloqueadoAunqueElCodigoSeaCorrecto() throws Exception {
        for (int i = 0; i < 5; i++) {
            codeLogin(new MockHttpSession(), user.username(), "0000-0000-0000-0000-0000-0000")
                .andExpect(status().isUnauthorized());
        }
        codeLogin(new MockHttpSession(), user.username(), user.enrollmentCode()).andExpect(status().isUnauthorized());
        assertThat(auditEvents()).doesNotContain("LOGIN_INSCRIPCION");
    }

    // --- Re-autenticación reciente ------------------------------------------------------

    @Test
    void registrarOtraPasskeyExigeHaberUsadoLaPasskeyHaceMenosDe5Minutos() throws Exception {
        mvc.perform(post("/webauthn/register/options").with(csrf())
                .with(authentication(passkeyLogin(user, Duration.ofMinutes(10)))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/webauthn/register/options").with(csrf())
                .with(authentication(passkeyLogin(user, Duration.ofMinutes(1)))))
            .andExpect(status().isOk());
    }

    @Test
    void generarCodigosDeRecuperacionExigePasskeyReciente() throws Exception {
        mvc.perform(post("/api/me/recovery-codes").with(csrf())
                .with(authentication(passkeyLogin(user, Duration.ofMinutes(10)))))
            .andExpect(status().isForbidden());
        String body = mvc.perform(post("/api/me/recovery-codes").with(csrf()).with(authentication(passkeyLogin(user))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) json(body).get("codes");
        assertThat(codes).hasSize(10).allMatch(c -> c.matches("([0-9A-F]{4}-){5}[0-9A-F]{4}"));

        // Un código de recuperación abre una sesión limitada.
        codeLogin(new MockHttpSession(), user.username(), codes.get(0)).andExpect(status().isOk());
        assertThat(auditEvents()).contains("CODIGOS_RECUPERACION_NUEVOS", "LOGIN_RECUPERACION");
    }

    // --- Ceremonia completa con un autenticador emulado ---------------------------------

    @Test
    void registroYLoginConPasskeyDeVerdad() throws Exception {
        ClientPlatform phone = new ClientPlatform(new Origin("http://localhost"),
            new WebAuthnAuthenticatorAdaptor(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR));

        // 1. Inscripción con el código y registro de la passkey.
        MockHttpSession enroll = new MockHttpSession();
        codeLogin(enroll, user.username(), user.enrollmentCode()).andExpect(status().isOk());
        Map<String, Object> options = json(mvc.perform(post("/webauthn/register/options").session(enroll).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        PublicKeyCredential<AuthenticatorAttestationResponse, ?> created = phone.create(creationOptions(options));
        String credentialId = B64URL.encodeToString(created.getRawId());
        mvc.perform(post("/webauthn/register").session(enroll).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("publicKey", Map.of("label", "Teléfono de prueba", "credential", Map.of(
                    "id", credentialId,
                    "rawId", credentialId,
                    "type", "public-key",
                    "clientExtensionResults", Map.of(),
                    "response", Map.of(
                        "attestationObject", B64URL.encodeToString(created.getResponse().getAttestationObject()),
                        "clientDataJSON", B64URL.encodeToString(created.getResponse().getClientDataJSON()),
                        "transports", List.of("internal"))))))))
            .andExpect(status().isOk());

        // 2. Login con la passkey en una sesión nueva.
        MockHttpSession session = new MockHttpSession();
        Map<String, Object> request = json(mvc.perform(post("/webauthn/authenticate/options").session(session).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        PublicKeyCredential<AuthenticatorAssertionResponse, ?> assertion = phone.get(requestOptions(request, created.getRawId()));
        AuthenticatorAssertionResponse r = assertion.getResponse();
        mvc.perform(post("/login/webauthn").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of(
                    "id", credentialId,
                    "rawId", credentialId,
                    "credType", "public-key",
                    "clientExtensionResults", Map.of(),
                    "response", Map.of(
                        "authenticatorData", B64URL.encodeToString(r.getAuthenticatorData()),
                        "clientDataJSON", B64URL.encodeToString(r.getClientDataJSON()),
                        "signature", B64URL.encodeToString(r.getSignature()))))))
            .andExpect(status().isOk());

        // La estrategia de sesión propia quedó conectada al filtro de passkeys (Spring trae una
        // nula): la sesión quedó registrada. La rotación del ID se prueba con Tomcat en SessionHttpTest.
        assertThat(sessionRegistry.getAllSessions(
                sessionRegistry.getAllPrincipals().stream()
                    .filter(p -> p instanceof org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity e
                        && e.getName().equals(user.username()))
                    .findFirst().orElseThrow(), false))
            .extracting(org.springframework.security.core.session.SessionInformation::getSessionId)
            .contains(session.getId());
        String me = mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(json(me)).containsEntry("username", user.username()).containsEntry("passkeys", 1);
        assertThat(auditEvents()).contains("PASSKEY_REGISTRADA", "LOGIN_OK");

        // 3. Logout invalida la sesión del lado del servidor.
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        assertThat(auditEvents()).contains("LOGOUT");
    }

    @Test
    void unaFirmaManipuladaNoIniciaSesion() throws Exception {
        mvc.perform(post("/login/webauthn").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("id", "AAAA", "rawId", "AAAA", "credType", "public-key",
                    "clientExtensionResults", Map.of(), "response", Map.of(
                        "authenticatorData", "AAAA", "clientDataJSON", "AAAA", "signature", "AAAA")))))
            .andExpect(status().isUnauthorized());
    }

    // --- helpers ------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions codeLogin(MockHttpSession session, String username,
                                                                         String code) throws Exception {
        // IP distinta por petición: el freno por IP no debe mezclar tests entre sí.
        String ip = "10.0.%d.%d".formatted(RANDOM.nextInt(250), RANDOM.nextInt(250));
        return mvc.perform(post("/login/code").session(session).with(csrf())
            .with(req -> { req.setRemoteAddr(ip); return req; })
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", username).param("code", code));
    }

    @SuppressWarnings("unchecked")
    private static PublicKeyCredentialCreationOptions creationOptions(Map<String, Object> o) {
        Map<String, Object> rp = (Map<String, Object>) o.get("rp");
        Map<String, Object> u = (Map<String, Object>) o.get("user");
        return new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity((String) rp.get("id"), (String) rp.get("name")),
            new PublicKeyCredentialUserEntity(B64URL_DECODER.decode((String) u.get("id")),
                (String) u.get("name"), (String) u.get("displayName")),
            new DefaultChallenge(B64URL_DECODER.decode((String) o.get("challenge"))),
            List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            60_000L, List.of(),
            new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.PREFERRED, UserVerificationRequirement.PREFERRED),
            AttestationConveyancePreference.NONE, null);
    }

    private static PublicKeyCredentialRequestOptions requestOptions(Map<String, Object> o, byte[] credentialId) {
        return new PublicKeyCredentialRequestOptions(
            new DefaultChallenge(B64URL_DECODER.decode((String) o.get("challenge"))), 60_000L,
            (String) o.get("rpId"),
            List.of(new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, credentialId, Set.of())),
            UserVerificationRequirement.PREFERRED, null);
    }

    private List<String> auditEvents() throws SQLException {
        try (Connection su = asSuperuser();
             var ps = su.prepareStatement("SELECT event_type FROM audit_log WHERE user_id = ? ORDER BY id")) {
            ps.setObject(1, user.id());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> events = new java.util.ArrayList<>();
                while (rs.next()) {
                    events.add(rs.getString(1));
                }
                return events;
            }
        }
    }
}

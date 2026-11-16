package com.casaayg.db;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;

/** MockMvc con la cadena de seguridad real, más utilidades para armar sesiones de prueba. */
public abstract class WebTestSupport extends PostgresTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** Sesión de passkey como la crea Spring, con la hora del login que se quiera. */
    protected static Authentication passkeyLogin(TestData.AppUser user, Duration ago) {
        ByteBuffer handle = ByteBuffer.allocate(16);
        handle.putLong(user.id().getMostSignificantBits()).putLong(user.id().getLeastSignificantBits());
        var entity = ImmutablePublicKeyCredentialUserEntity.builder()
            .id(new Bytes(handle.array())).name(user.username()).displayName("Test").build();
        return new WebAuthnAuthentication(entity, List.of(
            new SimpleGrantedAuthority("ROLE_USER"),
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
                .issuedAt(Instant.now().minus(ago)).build()));
    }

    protected static Authentication passkeyLogin(TestData.AppUser user) {
        return passkeyLogin(user, Duration.ZERO);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> json(String body) {
        return JSON.readValue(body, Map.class);
    }

    protected static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    protected static UUID uuid(Object value) {
        return UUID.fromString(String.valueOf(value));
    }
}

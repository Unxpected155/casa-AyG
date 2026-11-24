package com.casaayg.shared.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;

class SameUserReauthenticationTest {

    private final SameUserReauthentication check =
        new SameUserReauthentication(SecurityContextHolder.getContextHolderStrategy());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication signedIn(String name) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(name, null, "ROLE_USER");
        auth.setAuthenticated(true);
        return auth;
    }

    @Test
    void sinSesionEsUnLoginNormal() {
        assertThatCode(() -> check.onAuthentication(signedIn("ana"), null, null)).doesNotThrowAnyException();
    }

    @Test
    void sesionAnonimaEsUnLoginNormal() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
            "k", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatCode(() -> check.onAuthentication(signedIn("ana"), null, null)).doesNotThrowAnyException();
    }

    @Test
    void laMismaPersonaPuedeReautenticarse() {
        SecurityContextHolder.getContext().setAuthentication(signedIn("ana"));
        assertThatCode(() -> check.onAuthentication(signedIn("ana"), null, null)).doesNotThrowAnyException();
    }

    @Test
    void laPasskeyDeOtraPersonaSeRechaza() {
        SecurityContextHolder.getContext().setAuthentication(signedIn("ana"));
        assertThatThrownBy(() -> check.onAuthentication(signedIn("beto"), null, null))
            .isInstanceOf(SessionAuthenticationException.class);
    }
}

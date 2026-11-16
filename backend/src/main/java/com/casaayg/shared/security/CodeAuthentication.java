package com.casaayg.shared.security;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Login con código de inscripción o de recuperación. El resultado es una sesión LIMITADA
 * (autoridad ENROLL): solo puede registrar passkeys, nunca leer datos.
 */
public final class CodeAuthentication {

    private CodeAuthentication() {}

    /** Petición (sin autenticar) o resultado (autenticado). */
    public static final class Token extends AbstractAuthenticationToken {

        private final Object principal;
        private transient String code;
        private final transient String ip;

        Token(String username, String code, String ip) {
            super(List.of());
            this.principal = username;
            this.code = code;
            this.ip = ip;
            setAuthenticated(false);
        }

        Token(CodePrincipal principal) {
            super(List.of(new SimpleGrantedAuthority(CurrentUser.ENROLL)));
            this.principal = principal;
            this.ip = null;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return code;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }

        String ip() {
            return ip;
        }

        /** Spring busca la entidad WebAuthn por este nombre: tiene que ser el username. */
        @Override
        public String getName() {
            return principal instanceof CodePrincipal code ? code.username() : String.valueOf(principal);
        }

        @Override
        public void eraseCredentials() {
            super.eraseCredentials();
            code = null;
        }
    }

    public static final class Provider implements AuthenticationProvider {

        private final JdbcClient jdbc;
        private final LoginThrottle throttle;

        public Provider(JdbcClient jdbc, LoginThrottle throttle) {
            this.jdbc = jdbc;
            this.throttle = throttle;
        }

        @Override
        public Authentication authenticate(Authentication authentication) throws AuthenticationException {
            Token request = (Token) authentication;
            String username = String.valueOf(request.getPrincipal()).toLowerCase(java.util.Locale.ROOT);
            if (throttle.isBlocked(username, request.ip())) {
                throw new BadCredentialsException("bloqueado temporalmente");
            }
            var code = AuthCodes.normalize((String) request.getCredentials());
            if (code.isEmpty() || username.length() > 30) {
                throttle.recordFailure(username, request.ip());
                throw new BadCredentialsException("código inválido");
            }
            // Una sola sentencia: valida y marca el código como usado (no se reutiliza).
            var redeemed = jdbc.sql("SELECT user_id, purpose FROM auth_redeem_code(?, ?)")
                .params(username, AuthCodes.hash(code.get()))
                .query((rs, n) -> new CodePrincipal(rs.getObject("user_id", UUID.class), username, rs.getString("purpose")))
                .optional();
            if (redeemed.isEmpty()) {
                throttle.recordFailure(username, request.ip());
                throw new BadCredentialsException("código inválido");
            }
            throttle.reset(username);
            return new Token(redeemed.get());
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return Token.class.isAssignableFrom(authentication);
        }
    }
}

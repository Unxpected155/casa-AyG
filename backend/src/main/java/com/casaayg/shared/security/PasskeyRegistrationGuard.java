package com.casaayg.shared.security;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.function.ToIntFunction;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Registrar una passkey exige una sesión de código (ENROLL) o una passkey usada hace menos
 * de 5 minutos.
 *
 * Hallazgo de los tests: los filtros de registro de Spring atienden la petición ANTES de
 * AuthorizationFilter, así que una regla en authorizeHttpRequests no los protege. Este filtro
 * va antes que ellos (justo después de CSRF) y corta la petición.
 *
 * También revisa el tope de 10 passkeys por persona (V4) ANTES de la ceremonia: si no, el
 * trigger de la BD lo rechaza al final y Spring responde 500 con un mensaje que no explica nada.
 */
public class PasskeyRegistrationGuard extends OncePerRequestFilter {

    /** El tope de V4 (trigger user_credentials_guard). */
    public static final int MAX_PASSKEYS = 10;

    private static final RequestMatcher START =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/webauthn/register/options");

    private static final RequestMatcher REGISTRATION = new OrRequestMatcher(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/webauthn/register/options"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/webauthn/register"));

    private final SecurityContextHolderStrategy contexts;
    private final Clock clock;
    private final ToIntFunction<UUID> passkeyCount;

    public PasskeyRegistrationGuard(SecurityContextHolderStrategy contexts, Clock clock,
                                    ToIntFunction<UUID> passkeyCount) {
        this.contexts = contexts;
        this.clock = clock;
        this.passkeyCount = passkeyCount;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (REGISTRATION.matches(request)) {
            Authentication auth = contexts.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || CurrentUser.idOf(auth.getPrincipal()).isEmpty()) {
                response.sendError(HttpStatus.UNAUTHORIZED.value());
                return;
            }
            if (!allowed(auth, clock)) {
                response.sendError(HttpStatus.FORBIDDEN.value());
                return;
            }
            UUID user = CurrentUser.idOf(auth.getPrincipal()).orElseThrow();
            if (START.matches(request) && passkeyCount.applyAsInt(user) >= MAX_PASSKEYS) {
                response.sendError(HttpStatus.CONFLICT.value());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static boolean allowed(Authentication auth, Clock clock) {
        return CurrentUser.hasAuthority(auth, CurrentUser.ENROLL) || RecentAuthentication.isRecentPasskey(auth, clock);
    }
}

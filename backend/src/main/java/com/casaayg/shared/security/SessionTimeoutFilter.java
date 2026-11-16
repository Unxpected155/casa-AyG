package com.casaayg.shared.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Timeout ABSOLUTO de la sesión (el de inactividad lo pone server.servlet.session.timeout).
 * Aunque se use todos los días, una sesión de passkey dura como máximo 7 días y una sesión
 * de código (inscripción/recuperación) 10 minutos. Así un teléfono robado y desbloqueado
 * no queda con acceso para siempre.
 */
public class SessionTimeoutFilter extends OncePerRequestFilter {

    static final String LOGIN_AT = SessionTimeoutFilter.class.getName() + ".LOGIN_AT";

    private final SecurityContextHolderStrategy contexts;
    private final Duration passkeyMax;
    private final Duration codeMax;
    private final Clock clock;

    public SessionTimeoutFilter(SecurityContextHolderStrategy contexts, Duration passkeyMax, Duration codeMax, Clock clock) {
        this.contexts = contexts;
        this.passkeyMax = passkeyMax;
        this.codeMax = codeMax;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication auth = contexts.getContext().getAuthentication();
        if (session != null && auth != null && auth.isAuthenticated()
                && CurrentUser.idOf(auth.getPrincipal()).isPresent()) {
            Instant now = clock.instant();
            Object loginAt = session.getAttribute(LOGIN_AT);
            if (!(loginAt instanceof Instant start)) {
                session.setAttribute(LOGIN_AT, now);
            } else {
                Duration max = CurrentUser.hasAuthority(auth, CurrentUser.PASSKEY) ? passkeyMax : codeMax;
                if (start.plus(max).isBefore(now)) {
                    session.invalidate();
                    contexts.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }
}

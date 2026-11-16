package com.casaayg.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Re-autenticación reciente para acciones sensibles (registrar o borrar una passkey,
 * generar códigos de recuperación, aprobar la fusión de espacios).
 *
 * Una cookie robada sirve para leer datos, pero no para tomar la cuenta: estas acciones
 * exigen haber usado la passkey hace menos de 5 minutos.
 */
public final class RecentAuthentication {

    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private RecentAuthentication() {}

    public static boolean isRecentPasskey(Authentication auth, Clock clock) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Instant cutoff = clock.instant().minus(MAX_AGE);
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (granted instanceof FactorGrantedAuthority factor
                    && CurrentUser.PASSKEY.equals(factor.getAuthority())
                    && factor.getIssuedAt() != null
                    && factor.getIssuedAt().isAfter(cutoff)) {
                return true;
            }
        }
        return false;
    }

    /** Para los controladores: lanza si la última passkey no es reciente. */
    public static void require() {
        if (!isRecentPasskey(SecurityContextHolder.getContext().getAuthentication(), Clock.systemUTC())) {
            throw new ReauthenticationRequiredException();
        }
    }

    public static final class ReauthenticationRequiredException extends RuntimeException {
        ReauthenticationRequiredException() {
            super("se requiere volver a usar la passkey", null, false, false);
        }
    }
}

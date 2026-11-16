package com.casaayg.shared.security;

import java.util.UUID;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Cierra las sesiones de un usuario en otros dispositivos (por ejemplo, tras entrar con un
 * código de recuperación porque le robaron el teléfono). ConcurrentSessionFilter las
 * termina en su siguiente petición.
 */
@Component
public class SessionRevoker {

    private final SessionRegistry registry;

    SessionRevoker(SessionRegistry registry) {
        this.registry = registry;
    }

    /** @return cuántas sesiones se cerraron. */
    public int revokeOthers(UUID userId, String currentSessionId) {
        int revoked = 0;
        for (Object principal : registry.getAllPrincipals()) {
            if (!CurrentUser.idOf(principal).map(userId::equals).orElse(false)) {
                continue;
            }
            for (SessionInformation info : registry.getAllSessions(principal, false)) {
                if (!info.getSessionId().equals(currentSessionId)) {
                    info.expireNow();
                    revoked++;
                }
            }
        }
        return revoked;
    }
}

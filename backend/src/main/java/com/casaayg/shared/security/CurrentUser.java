package com.casaayg.shared.security;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

/**
 * Quién es el usuario de la petición actual.
 *
 * Para los datos (RLS) solo cuenta una sesión iniciada con <b>passkey</b>: una sesión abierta
 * con un código de inscripción o recuperación sirve únicamente para registrar passkeys.
 */
public final class CurrentUser {

    /** Lo agrega Spring Security al iniciar sesión con passkey (con la hora de emisión). */
    public static final String PASSKEY = FactorGrantedAuthority.WEBAUTHN_AUTHORITY;
    /** Sesión limitada abierta con un código: solo puede registrar passkeys. */
    public static final String ENROLL = "ENROLL";

    private CurrentUser() {}

    /** Usuario con sesión de passkey, o vacío. Es el que la BD recibe para RLS. */
    public static Optional<UUID> passkeyUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !hasAuthority(auth, PASSKEY)) {
            return Optional.empty();
        }
        return idOf(auth.getPrincipal());
    }

    public static UUID require() {
        return passkeyUserId().orElseThrow(() -> new AccessDeniedException("sin sesión"));
    }

    /** El id del usuario a partir del principal (de passkey o de código). */
    public static Optional<UUID> idOf(Object principal) {
        if (principal instanceof CodePrincipal code) {
            return Optional.of(code.id());
        }
        if (principal instanceof PublicKeyCredentialUserEntity entity) {
            return Optional.of(fromUserHandle(entity.getId().getBytes()));
        }
        return Optional.empty();
    }

    public static boolean hasAuthority(Authentication auth, String authority) {
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** El user handle de WebAuthn son los 16 bytes del UUID (ver user_handle() en V4). */
    static UUID fromUserHandle(byte[] handle) {
        if (handle.length != 16) {
            throw new IllegalArgumentException("user handle inválido");
        }
        ByteBuffer buffer = ByteBuffer.wrap(handle);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

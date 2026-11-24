package com.casaayg.shared.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Re-autenticarse con passkey (para borrar una passkey, generar códigos, etc.) tiene que ser con
 * una passkey de la MISMA persona que ya tiene la sesión. Las opciones ya piden solo sus passkeys
 * (Spring llena allowCredentials con las del usuario con sesión), pero el servidor no puede fiarse
 * de eso: si llega la passkey válida de otra persona, se rechaza en vez de cambiar de dueño la
 * sesión a mitad de una acción sensible.
 *
 * Corre como estrategia de sesión porque en ese punto Spring ya validó la passkey y todavía no
 * reemplazó el contexto: la autenticación de antes sigue en el holder. La excepción la convierte
 * el filtro en un login fallido (401 + auditoría), y la sesión original queda intacta.
 */
final class SameUserReauthentication implements SessionAuthenticationStrategy {

    private final SecurityContextHolderStrategy holder;

    SameUserReauthentication(SecurityContextHolderStrategy holder) {
        this.holder = holder;
    }

    @Override
    public void onAuthentication(Authentication incoming, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = holder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated() || current instanceof AnonymousAuthenticationToken) {
            return; // login normal: no había nadie con sesión
        }
        if (!current.getName().equals(incoming.getName())) {
            throw new SessionAuthenticationException("la passkey es de otra persona");
        }
    }
}

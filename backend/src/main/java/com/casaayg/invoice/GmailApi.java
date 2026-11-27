package com.casaayg.invoice;

import java.util.List;

/**
 * Lo que la app usa de Google: el permiso (OAuth 2.0 con PKCE) y la lectura del buzón con
 * {@code gmail.readonly}. Es una interfaz para poder probar el lector sin red.
 */
public interface GmailApi {

    String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    /** Hay client_id y client_secret configurados. */
    boolean configured();

    /** URL de Google para dar el permiso (la abre el navegador). */
    String authorizationUrl(String state, String codeChallenge);

    /** Cambia el código de la redirección por un refresh token. Falla si el permiso no es el esperado. */
    String exchangeCode(String code, String codeVerifier);

    /** Un access token de corta vida. */
    String accessToken(String refreshToken);

    String emailAddress(String accessToken);

    /** Ids de los correos que cumplen la búsqueda, los más nuevos primero. */
    List<String> listMessages(String accessToken, String query, int max);

    /** Los adjuntos XML de un correo (nombre y contenido), con tope de tamaño y cantidad. */
    List<Attachment> xmlAttachments(String accessToken, String messageId);

    /**
     * Un correo: a qué direcciones llegó (Delivered-To, To, Cc) y sus adjuntos XML. Con las
     * direcciones se sabe de quién es un tiquete sin cédula (casa+usuario@...).
     */
    default Mail mail(String accessToken, String messageId) {
        return new Mail(List.of(), xmlAttachments(accessToken, messageId));
    }

    /** Revoca el permiso en Google (desconectar). */
    void revoke(String refreshToken);

    record Attachment(String filename, byte[] content) {}

    record Mail(List<String> recipients, List<Attachment> attachments) {}
}

package com.casaayg.invoice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.json.JsonMapper;

/**
 * Google de verdad: OAuth 2.0 (código + PKCE S256) y la API de Gmail, solo lectura. El cliente
 * (client_id, client_secret, redirect_uris) es el JSON que se descarga de Google Cloud, guardado
 * como el secreto {@code google_oauth_client}.
 */
@Component
class GoogleGmailApi implements GmailApi {

    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String REVOKE = "https://oauth2.googleapis.com/revoke";
    private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final int MAX_ATTACHMENTS = 6;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClientFile(Client web) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Client(@JsonProperty("client_id") String clientId, @JsonProperty("client_secret") String clientSecret,
                  @JsonProperty("redirect_uris") List<String> redirectUris) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("refresh_token") String refreshToken, String scope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(String emailAddress) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageList(List<MessageRef> messages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageRef(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(Part payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String filename, String mimeType, Body body, List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Body(String attachmentId, Integer size, String data) {}

    private final Client client;
    private final RestClient http;

    GoogleGmailApi(@Value("${google_oauth_client:}") String clientJson) {
        this.client = parse(clientJson);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    private static Client parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Client c = JsonMapper.builder().build().readValue(json, ClientFile.class).web();
            boolean ok = c != null && c.clientId() != null && c.clientSecret() != null
                && c.redirectUris() != null && !c.redirectUris().isEmpty()
                && c.redirectUris().getFirst().startsWith("https://");
            return ok ? c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean configured() {
        return client != null;
    }

    private Client client() {
        if (client == null) {
            throw new IllegalStateException("falta google_oauth_client");
        }
        return client;
    }

    @Override
    public String authorizationUrl(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(AUTH)
            .queryParam("client_id", client().clientId())
            .queryParam("redirect_uri", client().redirectUris().getFirst())
            .queryParam("response_type", "code")
            .queryParam("scope", SCOPE)
            .queryParam("access_type", "offline")
            .queryParam("prompt", "consent")
            .queryParam("include_granted_scopes", "false")
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .encode().build().toUriString();
    }

    @Override
    public String exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", client().clientId());
        form.add("client_secret", client().clientSecret());
        form.add("redirect_uri", client().redirectUris().getFirst());
        form.add("grant_type", "authorization_code");
        form.add("code_verifier", codeVerifier);
        TokenResponse t = post(TOKEN, form);
        // Solo el permiso que pedimos: ni más (no se acepta) ni menos (no serviría).
        if (t == null || t.refreshToken() == null || t.scope() == null
                || !Set.of(t.scope().trim().split("\\s+")).equals(Set.of(SCOPE))) {
            throw new IllegalStateException("permiso inesperado");
        }
        return t.refreshToken();
    }

    @Override
    public String accessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", client().clientId());
        form.add("client_secret", client().clientSecret());
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        TokenResponse t = post(TOKEN, form);
        if (t == null || t.accessToken() == null) {
            throw new IllegalStateException("sin access token");
        }
        return t.accessToken();
    }

    private TokenResponse post(String url, MultiValueMap<String, String> form) {
        return http.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
            .retrieve().body(TokenResponse.class);
    }

    @Override
    public String emailAddress(String accessToken) {
        Profile p = http.get().uri(GMAIL + "/profile").headers(h -> h.setBearerAuth(accessToken))
            .retrieve().body(Profile.class);
        return p == null ? null : p.emailAddress();
    }

    @Override
    public List<String> listMessages(String accessToken, String query, int max) {
        URI uri = UriComponentsBuilder.fromUriString(GMAIL + "/messages")
            .queryParam("q", query).queryParam("maxResults", max).encode().build().toUri();
        MessageList list = http.get().uri(uri).headers(h -> h.setBearerAuth(accessToken))
            .retrieve().body(MessageList.class);
        if (list == null || list.messages() == null) {
            return List.of();
        }
        return list.messages().stream().map(MessageRef::id).filter(id -> id != null && id.matches("[A-Za-z0-9_-]{1,64}"))
            .toList();
    }

    @Override
    public List<Attachment> xmlAttachments(String accessToken, String messageId) {
        if (!messageId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("id");
        }
        Message m = http.get().uri(GMAIL + "/messages/" + messageId + "?format=full")
            .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Message.class);
        List<Part> xml = new ArrayList<>();
        if (m != null && m.payload() != null) {
            collect(m.payload(), xml, 0);
        }
        List<Attachment> out = new ArrayList<>();
        for (Part p : xml.subList(0, Math.min(xml.size(), MAX_ATTACHMENTS))) {
            String data = p.body().data();
            if (data == null && p.body().attachmentId() != null && p.body().attachmentId().matches("[A-Za-z0-9_-]{1,1024}")) {
                Body b = http.get().uri(GMAIL + "/messages/" + messageId + "/attachments/" + p.body().attachmentId())
                    .headers(h -> h.setBearerAuth(accessToken)).retrieve().body(Body.class);
                data = b == null ? null : b.data();
            }
            if (data != null && data.length() <= SafeXml.MAX_BYTES * 4 / 3 + 4) {
                out.add(new Attachment(p.filename(), Base64.getUrlDecoder().decode(data.getBytes(StandardCharsets.US_ASCII))));
            }
        }
        return out;
    }

    /** Adjuntos .xml (no muy grandes), recorriendo el árbol MIME con un límite de profundidad. */
    private static void collect(Part part, List<Part> out, int depth) {
        if (depth > 8 || out.size() >= MAX_ATTACHMENTS * 2) {
            return;
        }
        String name = part.filename() == null ? "" : part.filename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") && part.body() != null
                && (part.body().size() == null || part.body().size() <= SafeXml.MAX_BYTES)) {
            out.add(part);
        }
        if (part.parts() != null) {
            for (Part p : part.parts()) {
                collect(p, out, depth + 1);
            }
        }
    }

    @Override
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        http.post().uri(REVOKE).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
    }
}

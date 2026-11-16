package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * Contra el servidor REAL (Tomcat), no MockMvc: así se prueban los atributos de las cookies,
 * los headers de seguridad y la rotación del ID de sesión (fijación de sesión).
 */
// Contexto propio: el csrf() de los tests MockMvc cambia el repositorio CSRF del contexto
// compartido, y aquí se necesita el comportamiento real (cookie XSRF-TOKEN).
@TestPropertySource(properties = "casaayg.test.contexto=http-real")
class SessionHttpTest extends PostgresTestBase {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void lasPaginasLlevanHeadersDeSeguridad() throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri("/login")).GET().build());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Security-Policy")).get().asString()
            .contains("default-src 'self'", "script-src 'self'", "frame-ancestors 'none'")
            .doesNotContain("unsafe-inline");
        assertThat(res.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(res.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(res.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        // La cookie CSRF la lee el JavaScript (no HttpOnly), pero solo viaja al mismo sitio.
        assertThat(setCookie(res, "XSRF-TOKEN")).get().asString().contains("Secure", "SameSite=Strict");
    }

    @Test
    void elIdDeSesionCambiaAlIniciarSesionYLaCookieEsHostOnly() throws Exception {
        TestData.AppUser user;
        try (Connection su = asSuperuser()) {
            user = TestData.adminCreateUser(su, "Persona HTTP");
        }
        String csrf = cookieValue(setCookie(send(HttpRequest.newBuilder(uri("/login")).GET().build()), "XSRF-TOKEN").orElseThrow());

        // 1. Una sesión ANÓNIMA (como la que un atacante intentaría "plantar").
        HttpResponse<String> options = send(post("/webauthn/authenticate/options", csrf, null, ""));
        String planted = cookieValue(setCookie(options, "__Host-casaayg").orElseThrow());
        assertThat(setCookie(options, "__Host-casaayg")).get().asString()
            .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/").doesNotContain("Domain=");

        // 2. Login con código usando esa sesión: el servidor entrega un ID NUEVO.
        HttpResponse<String> login = send(post("/login/code", csrf, planted,
            "username=" + user.username() + "&code=" + user.enrollmentCode()));
        assertThat(login.statusCode()).isEqualTo(200);
        String fresh = cookieValue(setCookie(login, "__Host-casaayg").orElseThrow());
        assertThat(fresh).isNotEqualTo(planted);

        // 3. El ID plantado no quedó autenticado.
        HttpResponse<String> withPlanted = send(HttpRequest.newBuilder(uri("/api/me"))
            .header("Cookie", "__Host-casaayg=" + planted).GET().build());
        assertThat(withPlanted.statusCode()).isEqualTo(401);
    }

    @Test
    void unCuerpoGiganteSeRechazaSinLeerlo() throws Exception {
        String csrf = cookieValue(setCookie(send(HttpRequest.newBuilder(uri("/login")).GET().build()), "XSRF-TOKEN").orElseThrow());
        String huge = "username=x&code=" + "A".repeat(200_000);
        assertThat(send(post("/login/code", csrf, null, huge)).statusCode()).isEqualTo(413);
    }

    // --- helpers ------------------------------------------------------------------------

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpRequest post(String path, String csrf, String session, String form) {
        String cookies = "XSRF-TOKEN=" + csrf + (session == null ? "" : "; __Host-casaayg=" + session);
        return HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-XSRF-TOKEN", csrf)
            .header("Cookie", cookies)
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> setCookie(HttpResponse<?> res, String name) {
        List<String> all = res.headers().allValues("Set-Cookie");
        return all.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b);
    }

    private static String cookieValue(String setCookie) {
        Matcher m = Pattern.compile("^[^=]+=([^;]*)").matcher(setCookie);
        return m.find() ? m.group(1) : "";
    }
}

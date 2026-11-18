package com.casaayg.db;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;

/** Los encabezados de seguridad que revisa ZAP salen en todas las respuestas, también sin sesión. */
class SecurityHeadersTest extends WebTestSupport {

    @Test
    void encabezadosDeSeguridadYAislamientoEntreOrigenes() throws Exception {
        for (String path : new String[] {"/login", "/api/me"}) {
            mvc.perform(get(path))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Embedder-Policy", "require-corp"));
        }
    }
}

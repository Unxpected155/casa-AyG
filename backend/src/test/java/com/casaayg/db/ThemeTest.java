package com.casaayg.db;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** El tema de la app (V30): por persona, Automático por defecto. */
class ThemeTest extends WebTestSupport {

    TestData.AppUser a;
    TestData.AppUser b;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
        }
    }

    @Test
    void cadaQuienEligeSuTemaYEmpiezaEnAutomatico() throws Exception {
        as(a, get("/api/me")).andExpect(jsonPath("$.theme").value("AUTO"));
        as(a, put("/api/me/theme").content("{\"theme\":\"OSCURO\"}")).andExpect(status().isNoContent());
        as(a, get("/api/me")).andExpect(jsonPath("$.theme").value("OSCURO"));
        as(b, get("/api/me")).andExpect(jsonPath("$.theme").value("AUTO"));

        as(a, put("/api/me/theme").content("{\"theme\":\"MORADO\"}")).andExpect(status().isBadRequest());
        as(a, put("/api/me/theme").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/theme").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"theme\":\"CLARO\"}")).andExpect(status().isUnauthorized());
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

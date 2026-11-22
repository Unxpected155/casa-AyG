package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.casaayg.invoice.TaxIdService;

/** V15: la cédula se guarda solo como huella, cada quien toca la suya y nunca vuelve. */
class TaxIdTest extends WebTestSupport {

    @Autowired
    TaxIdService taxIds;

    TestData.AppUser ana;
    TestData.AppUser beto;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            su.createStatement().execute("DELETE FROM tax_id");
            ana =TestData.adminCreateUser(su, "Ana");
            beto = TestData.adminCreateUser(su, "Beto");
        }
    }

    @Test
    void laCedulaSeGuardaComoHuellaYSirveParaReconocerFacturas() throws Exception {
        as(ana, get("/api/me/tax-id")).andExpect(jsonPath("$.set").value(false)).andExpect(jsonPath("$.available").value(true));
        as(ana, put("/api/me/tax-id").content(toJson(Map.of("number", "1-9999-9999")))).andExpect(status().isNoContent());
        as(ana, get("/api/me/tax-id")).andExpect(content().json("{\"set\":true,\"available\":true}", true));
        as(beto, get("/api/me/tax-id")).andExpect(jsonPath("$.set").value(false));

        // En la BD no queda la cédula, ni con guiones ni sin ellos.
        try (Connection su = asSuperuser(); var rs = su.createStatement().executeQuery(
                "SELECT encode(id_hmac, 'hex'), encode(id_hmac, 'escape') FROM tax_id")) {
            while (rs.next()) {
                assertThat(rs.getString(1) + rs.getString(2)).doesNotContain("199999999").doesNotContain("9999-9999");
            }
        }
        // Como viene en el XML de Hacienda (sin guiones, con o sin el 0 inicial).
        assertThat(taxIds.ownerOf("199999999")).contains(ana.id());
        assertThat(taxIds.ownerOf("0199999999")).contains(ana.id());
        assertThat(taxIds.ownerOf("188888888")).isEqualTo(Optional.empty());

        as(ana, delete("/api/me/tax-id")).andExpect(status().isNoContent());
        assertThat(taxIds.ownerOf("199999999")).isEmpty();
    }

    @Test
    void dosPersonasNoPuedenTenerLaMismaCedula() throws Exception {
        as(ana, put("/api/me/tax-id").content(toJson(Map.of("number", "199999999")))).andExpect(status().isNoContent());
        as(beto, put("/api/me/tax-id").content(toJson(Map.of("number", "1 9999 9999"))))
            .andExpect(status().isConflict());
        assertThat(taxIds.ownerOf("199999999")).contains(ana.id());
        // Ana sí puede cambiar la suya.
        as(ana, put("/api/me/tax-id").content(toJson(Map.of("number", "155555555")))).andExpect(status().isNoContent());
        assertThat(taxIds.ownerOf("199999999")).isEmpty();
    }

    @Test
    void unaCedulaMalEscritaSeRechaza() throws Exception {
        for (String bad : new String[] {"12345", "abc123456789", "1234567890123", "0000000000", "1;DROP TABLE x"}) {
            as(ana, put("/api/me/tax-id").content(toJson(Map.of("number", bad)))).andExpect(status().is4xxClientError());
        }
        as(ana, get("/api/me/tax-id")).andExpect(jsonPath("$.set").value(false));
    }

    @Test
    void laAppNoPuedeLeerLaTablaDirecto() throws Exception {
        as(ana, put("/api/me/tax-id").content(toJson(Map.of("number", "199999999")))).andExpect(status().isNoContent());
        try (Connection app = asApp()) {
            assertThatThrownBy(() -> app.createStatement().executeQuery("SELECT * FROM casaayg.tax_id"))
                .hasMessageContaining("permission denied");
            // Sin sesión no se puede poner una cédula.
            assertThatThrownBy(() -> app.createStatement().executeQuery("SELECT casaayg.tax_id_set('\\x00'::bytea)"))
                .hasMessageContaining("sin sesión");
        }
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Fase 4: autorización de la API. A y B son usuarios SIN espacio compartido.
 * Regla: todo lo de A es invisible para B, y la respuesta es 404 (no 403), igual que si no existiera.
 */
class ApiAuthorizationTest extends WebTestSupport {

    TestData.AppUser a;
    TestData.AppUser b;
    UUID categoryA;
    UUID transactionA;
    UUID placeA;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            categoryA = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            placeA = TestData.newPlace(su, a.personalSpace(), a.id());
        }
        String created = as(a, post(tx(a.personalSpace())).content(toJson(newTx(categoryA, "12500.50", "CRC"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        transactionA = uuid(json(created).get("id"));
    }

    // --- IDOR ----------------------------------------------------------------------------

    @Test
    void bNoPuedeLeerEditarNiBorrarElMovimientoDeA() throws Exception {
        String url = tx(a.personalSpace()) + "/" + transactionA;
        as(b, get(url)).andExpect(status().isNotFound());
        as(b, put(url).content(toJson(newTx(categoryA, "1", "CRC")))).andExpect(status().isNotFound());
        as(b, delete(url)).andExpect(status().isNotFound());
        // Sigue intacto para A.
        String body = as(a, get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(body)).containsEntry("amount", 12500.50);
    }

    @Test
    void elIdDelMovimientoDeAConElEspacioDeBTampocoSirve() throws Exception {
        // B usa SU espacio (del que sí es miembro) con el id de A: la consulta es por (id, espacio).
        as(b, get(tx(b.personalSpace()) + "/" + transactionA)).andExpect(status().isNotFound());
        as(b, delete(tx(b.personalSpace()) + "/" + transactionA)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/categories", "/transactions?year=2026&month=11", "/budgets/2026/11",
                            "/summary?year=2026&month=11", "/export"})
    void bNoVeNadaDelEspacioDeA(String path) throws Exception {
        as(b, get("/api/spaces/" + a.personalSpace() + path)).andExpect(status().isNotFound());
    }

    @Test
    void bNoPuedeEscribirEnElEspacioDeA() throws Exception {
        String base = "/api/spaces/" + a.personalSpace();
        as(b, post(base + "/categories").content("{\"name\":\"X\",\"group\":\"DESEO\"}")).andExpect(status().isNotFound());
        as(b, put(base + "/categories/" + categoryA).content("{\"name\":\"X\",\"group\":\"DESEO\"}")).andExpect(status().isNotFound());
        as(b, delete(base + "/categories/" + categoryA)).andExpect(status().isNotFound());
        as(b, post(base + "/transactions").content(toJson(newTx(categoryA, "1", "CRC")))).andExpect(status().isNotFound());
        as(b, put(base + "/budgets/2026/11").content("{\"lines\":[]}")).andExpect(status().isNotFound());
    }

    @Test
    void usarLaCategoriaDeOtroEspacioResponde404() throws Exception {
        as(b, post(tx(b.personalSpace())).content(toJson(newTx(categoryA, "1", "CRC")))).andExpect(status().isNotFound());
    }

    @Test
    void laListaDeEspaciosSoloTraeLosPropios() throws Exception {
        String body = as(b, get("/api/spaces")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(b.personalSpace().toString()).doesNotContain(a.personalSpace().toString());
    }

    // --- Mass assignment y validación -----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"spaceId", "createdBy", "id", "source", "userId"})
    void unCampoQueLaApiNoEsperaSeRechazaCon400(String extra) throws Exception {
        Map<String, Object> body = new HashMap<>(newTx(categoryA, "1", "CRC"));
        body.put(extra, b.id().toString());
        as(a, post(tx(a.personalSpace())).content(toJson(body))).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.001", "1000000000000", "abc"})
    void montosInvalidosSeRechazanSinRevelarDetalles(String amount) throws Exception {
        String raw = toJson(newTx(categoryA, "1", "CRC")).replace("\"1\"", "\"" + amount + "\"");
        String response = as(a, post(tx(a.personalSpace())).content(raw))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("\"" + amount + "\"").doesNotContain("amount")
            .doesNotContainIgnoringCase("exception").doesNotContain("SQL");
    }

    @Test
    void textosConCaracteresDeControlSeRechazan() throws Exception {
        Map<String, Object> body = new HashMap<>(newTx(categoryA, "1", "CRC"));
        body.put("description", "Compra\n=HYPERLINK(\"http://x\")");
        as(a, post(tx(a.personalSpace())).content(toJson(body))).andExpect(status().isBadRequest());
    }

    @Test
    void laPaginacionTieneTope() throws Exception {
        as(a, get(tx(a.personalSpace()) + "?year=2026&month=11&size=1000000")).andExpect(status().isBadRequest());
        as(a, get(tx(a.personalSpace()) + "?year=2026&month=11&size=100")).andExpect(status().isOk());
    }

    @Test
    void unIdMalFormadoResponde404() throws Exception {
        as(a, get(tx(a.personalSpace()) + "/no-es-un-uuid")).andExpect(status().isNotFound());
    }

    // --- Reglas de negocio -----------------------------------------------------------------

    @Test
    void unaCategoriaConMovimientosNoSeBorraPeroSeDesactiva() throws Exception {
        String url = "/api/spaces/" + a.personalSpace() + "/categories/" + categoryA;
        as(a, delete(url)).andExpect(status().isConflict());
        as(a, put(url).content("{\"name\":\"Supermercado\",\"group\":\"NECESIDAD\",\"active\":false}"))
            .andExpect(status().isNoContent());
    }

    @Test
    void sePuedenAgregarYBorrarCategorias() throws Exception {
        String base = "/api/spaces/" + a.personalSpace() + "/categories";
        String created = as(a, post(base).content("{\"name\":\"Mascotas\",\"group\":\"NECESIDAD\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        as(a, post(base).content("{\"name\":\"mascotas\",\"group\":\"DESEO\"}")).andExpect(status().isConflict());
        as(a, delete(base + "/" + json(created).get("id"))).andExpect(status().isNoContent());
    }

    @Test
    void elPresupuestoSeGuardaYSeLee() throws Exception {
        String url = "/api/spaces/" + a.personalSpace() + "/budgets/2026/11";
        as(a, put(url).content(toJson(Map.of("lines", List.of(Map.of("categoryId", categoryA, "plannedAmount", 200000))))))
            .andExpect(status().isOk());
        String body = as(a, get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(categoryA.toString(), "200000");
    }

    @Test
    void laExportacionIncluyeTodoYQuedaAuditada() throws Exception {
        String body = as(a, get("/api/spaces/" + a.personalSpace() + "/export"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(transactionA.toString(), "Supermercado");
        try (Connection su = asSuperuser();
             var rs = su.createStatement().executeQuery(
                 "SELECT count(*) FROM audit_log WHERE event_type = 'EXPORTACION' AND user_id = '" + a.id() + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        Authentication auth = passkeyLogin(user);
        return mvc.perform(request.with(authentication(auth)).with(csrf()).contentType(MediaType.APPLICATION_JSON));
    }

    private static String tx(UUID space) {
        return "/api/spaces/" + space + "/transactions";
    }

    private Map<String, Object> newTx(UUID category, String amount, String currency) {
        return Map.of("categoryId", category.toString(), "date", LocalDate.now().withDayOfMonth(1).toString(),
            "description", "Compra de prueba", "amount", amount, "currency", currency, "placeId", placeA.toString());
    }
}

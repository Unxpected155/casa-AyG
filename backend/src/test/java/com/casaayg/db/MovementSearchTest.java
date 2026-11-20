package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Buscar y filtrar movimientos: "¿cuánto gastamos en Uber este mes?". */
class MovementSearchTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now();

    TestData.AppUser a;
    TestData.AppUser b;
    UUID transporte;
    UUID supermercado;
    UUID cuenta;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            transporte = TestData.categoryNamed(su, a.personalSpace(), "Transporte");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            cuenta = TestData.newPlace(su, a.personalSpace(), a.id());
        }
        tx(transporte, "Uber al centro", "3500", TODAY);
        tx(transporte, "UBER aeropuerto", "12000", TODAY);
        tx(transporte, "Uber del mes pasado", "4000", TODAY.withDayOfMonth(1).minusMonths(1));
        tx(supermercado, "Automercado 100%", "20000", TODAY);
    }

    @Test
    void buscaPorTextoSinImportarMayusculasYSumaLoEncontrado() throws Exception {
        Map<String, Object> page = search("q=uber");
        assertThat(items(page)).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) page.get("totals");
        assertThat(((Number) totals.get("expenseCrc")).doubleValue()).isEqualTo(15500.0);

        // En toda la historia aparece también el del mes pasado.
        assertThat(items(search("q=uber&all=true"))).hasSize(3);
    }

    @Test
    void buscaSinImportarTildes() throws Exception {
        tx(supermercado, "Súper de la semana", "1000", TODAY);
        tx(supermercado, "Café y almuerzo", "1000", TODAY);
        assertThat(items(search("q=super"))).hasSize(1);
        assertThat(items(search("q=café"))).hasSize(1);
        assertThat(items(search("q=cafe"))).hasSize(1);
    }

    @Test
    void filtraPorCategoriaYLosComodinesNoSonComodines() throws Exception {
        assertThat(items(search("categoryId=" + supermercado))).hasSize(1);
        // "%" se busca literal: solo el que tiene "100%".
        // (MockMvc codifica la URL: "%" llega al servidor como el carácter %, no como comodín.)
        assertThat(items(search("q=%"))).hasSize(1);
        assertThat(items(search("q=_"))).isEmpty();
    }

    @Test
    void bNoPuedeBuscarEnElEspacioDeA() throws Exception {
        as(b, get(url() + "&q=uber")).andExpect(status().isNotFound());
    }

    private String url() {
        return "/api/spaces/" + a.personalSpace() + "/transactions?year=" + TODAY.getYear() + "&month=" + TODAY.getMonthValue();
    }

    private Map<String, Object> search(String query) throws Exception {
        return json(as(a, get(url() + "&" + query)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private void tx(UUID category, String description, String amount, LocalDate date) throws Exception {
        as(a, post("/api/spaces/" + a.personalSpace() + "/transactions").content(toJson(Map.of("categoryId", category.toString(),
            "date", date.toString(), "description", description, "amount", amount, "currency", "CRC",
            "placeId", cuenta.toString()))))
            .andExpect(status().isCreated());
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

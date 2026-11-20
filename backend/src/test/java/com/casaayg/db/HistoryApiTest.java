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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Historia por la API: solo del propio espacio, y el dinero movido no cuenta como gasto. */
class HistoryApiTest extends WebTestSupport {

    @Test
    void historiaDelPropioEspacioSinContarElDineroMovido() throws Exception {
        TestData.AppUser a;
        TestData.AppUser b;
        UUID supermercado;
        UUID cuenta;
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            cuenta = TestData.newPlace(su, a.personalSpace(), a.id());
        }
        LocalDate today = LocalDate.now();
        String base = "/api/spaces/" + a.personalSpace();
        as(a, post(base + "/transactions").content(toJson(Map.of("categoryId", supermercado.toString(),
            "date", today.toString(), "description", "Súper", "amount", "1000", "currency", "CRC",
            "placeId", cuenta.toString()))))
            .andExpect(status().isCreated());
        UUID from = place(a, "BAC");
        UUID to = place(a, "Ahorros");
        as(a, post(base + "/transfers").content(toJson(Map.of("from", from.toString(), "to", to.toString(),
            "date", today.toString(), "amount", "500")))).andExpect(status().isCreated());

        String url = base + "/history?year=" + today.getYear() + "&month=" + today.getMonthValue();
        Map<String, Object> h = json(as(a, get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> months = (List<Map<String, Object>>) h.get("months");
        assertThat(months).hasSize(6);
        assertThat(((Number) months.get(5).get("expenses")).doubleValue()).isEqualTo(1000.0);

        as(b, get(url)).andExpect(status().isNotFound());
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private UUID place(TestData.AppUser user, String name) throws Exception {
        String body = as(user, post("/api/spaces/" + user.personalSpace() + "/places").content(toJson(Map.of(
                "name", name, "kind", "BANCO", "currency", "CRC", "openingBalance", "1000",
                "openingDate", LocalDate.now().toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * ADR-005: si alguien sale del espacio familiar, se lleva TODO. La exportación incluye cada
 * tabla con datos del espacio (lugares con su CDP, metas con partes y lugares, pagos fijos),
 * y nada de otros espacios.
 */
class ExportTest extends WebTestSupport {

    @Test
    void laExportacionLlevaTodoLoDelEspacioYNadaAjeno() throws Exception {
        TestData.AppUser a;
        TestData.AppUser b;
        UUID servicios;
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            servicios = TestData.categoryNamed(su, a.personalSpace(), "Servicios (luz, agua, internet)");
        }
        String base = "/api/spaces/" + a.personalSpace();
        UUID bcr = place(a, "BCR", "BANCO");
        UUID cdp = place(a, "CDP ahorro", "CDP");
        as(a, put(base + "/places/" + cdp + "/cdp").content(toJson(Map.of("rate", "2.85", "termDays", 30,
            "dayBasis", 360, "taxPct", "15", "maturesOn", LocalDate.now().plusDays(20).toString(),
            "payoutPlace", bcr.toString())))).andExpect(status().isOk());
        as(a, post(base + "/goals").content(toJson(Map.of("name", "Casarnos", "kind", "NORMAL", "targetAmount", "5000000",
            "currency", "CRC", "placeIds", List.of(cdp.toString()),
            "parts", List.of(Map.of("name", "Luna de miel", "amount", "1000000")))))).andExpect(status().isCreated());
        as(a, post(base + "/recurring").content(toJson(Map.of("name", "Internet", "categoryId", servicios.toString(),
            "dueDay", 15, "currency", "CRC", "notifyDaysBefore", 1)))).andExpect(status().isCreated());
        // Algo de B que NO debe salir en la exportación de A.
        as(b, post("/api/spaces/" + b.personalSpace() + "/goals").content(toJson(Map.of("name", "Secreto de B",
            "kind", "NORMAL", "targetAmount", "1", "currency", "CRC")))).andExpect(status().isCreated());

        Map<String, Object> export = json(as(a, get(base + "/export")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(export).containsKeys("format", "categories", "budgets", "transactions", "places", "transfers",
            "goals", "goalParts", "goalPlaces", "recurringPayments");
        String text = toJson(export);
        assertThat(text).contains("Casarnos", "Luna de miel", "Internet", "\"cdpRate\":\"2.8500\"", cdp.toString())
            .doesNotContain("Secreto de B");
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private UUID place(TestData.AppUser user, String name, String kind) throws Exception {
        String body = as(user, post("/api/spaces/" + user.personalSpace() + "/places").content(toJson(Map.of(
                "name", name, "kind", kind, "currency", "CRC", "openingBalance", "1000",
                "openingDate", LocalDate.now().toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }
}

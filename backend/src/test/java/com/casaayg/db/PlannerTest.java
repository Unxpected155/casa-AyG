package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** El planeador de metas (V29): «¿Cuándo llego?» y el plan opcional para «¿Voy como planeé?». */
class PlannerTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Costa_Rica"));

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
    void recomiendaElMinimoParaLlegarATiempoYRepartePorPrioridad() throws Exception {
        UUID bank = place("BCR", "BANCO", "1000000");
        UUID sobreBoda = place("Sobre boda", "SOBRE", "0");
        UUID sobreViaje = place("Sobre viaje", "SOBRE", "0");
        LocalDate fecha = TODAY.withDayOfMonth(1).plusMonths(5).plusDays(9);
        goal("Boda", "120000", fecha, sobreBoda);
        goal("Viaje", "300000", null, sobreViaje);

        Map<String, Object> p = planner("");
        assertThat(num(p, "recommended")).isEqualByComparingTo("20000");
        assertThat(p).containsEntry("recommendedGoal", "Boda");
        assertThat(num(p, "monthly")).isEqualByComparingTo("20000");
        List<Map<String, Object>> items = items(p);
        assertThat(items).extracting(i -> i.get("name")).containsExactly("Boda", "Viaje");
        assertThat(items.get(0)).containsEntry("onTime", true);
        assertThat(items.get(1).get("onTime")).isNull();

        // Con lo recomendado, la boda recibe justo lo que necesita y el viaje espera su turno.
        assertThat(num(items.get(0), "monthlyCrc")).isEqualByComparingTo("20000");
        assertThat(num(items.get(1), "monthlyCrc")).isEqualByComparingTo("0");
        // Con ₡50 000, lo que sobra adelanta a la más importante: la boda llega antes.
        items = items(planner("?monthly=50000"));
        assertThat(num(items.get(0), "monthlyCrc")).isEqualByComparingTo("50000");
        assertThat(items.get(0)).containsEntry("onTime", true);
        assertThat(bank).isNotNull();
    }

    @Test
    void elPlanEsOpcionalYComparaLoPlaneadoConLoQueEntro() throws Exception {
        UUID bank = place("BCR", "BANCO", "1000000");
        UUID sobre = place("Sobre boda", "SOBRE", "0");
        goal("Boda", "500000", null, sobre);

        // Sin plan no hay comparación.
        as(a, get(base() + "/planner/plan")).andExpect(status().isNoContent());

        Map<String, Object> progress = json(as(a, post(base() + "/planner/plan").content("{\"monthly\":\"100000\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(num(progress, "monthly")).isEqualByComparingTo("100000");
        assertThat(months(progress)).hasSize(1);
        assertThat(num(months(progress).get(0), "planned")).isEqualByComparingTo("100000");
        assertThat(num(months(progress).get(0), "actual")).isEqualByComparingTo("0");

        // Apartan ₡60 000 este mes: se ve tal cual, sin juicio.
        as(a, post(base() + "/transfers").content(toJson(Map.of("from", bank.toString(), "to", sobre.toString(),
            "date", TODAY.toString(), "amount", "60000")))).andExpect(status().isCreated());
        progress = json(as(a, get(base() + "/planner/plan")).andReturn().getResponse().getContentAsString());
        assertThat(num(months(progress).get(0), "actual")).isEqualByComparingTo("60000");
        assertThat(months(progress).get(0)).containsEntry("partial", true);

        // B no lo ve ni lo toca; A lo puede borrar.
        as(b, get(base() + "/planner")).andExpect(status().isNotFound());
        as(b, get(base() + "/planner/plan")).andExpect(status().isNotFound());
        as(b, delete(base() + "/planner/plan")).andExpect(status().isNotFound());
        as(a, delete(base() + "/planner/plan")).andExpect(status().isNoContent());
        as(a, get(base() + "/planner/plan")).andExpect(status().isNoContent());
    }

    @Test
    void unMontoNegativoNoSeAcepta() throws Exception {
        as(a, get(base() + "/planner?monthly=-1")).andExpect(status().isBadRequest());
    }

    // --- utilidades ----------------------------------------------------------------------

    private String base() {
        return "/api/spaces/" + a.personalSpace();
    }

    private Map<String, Object> planner(String query) throws Exception {
        return json(as(a, get(base() + "/planner" + query)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> planner) {
        return (List<Map<String, Object>>) planner.get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> months(Map<String, Object> progress) {
        return (List<Map<String, Object>>) progress.get("months");
    }

    private UUID place(String name, String kind, String opening) throws Exception {
        String body = as(a, post(base() + "/places").content(toJson(Map.of("name", name, "kind", kind,
                "currency", "CRC", "openingBalance", opening, "openingDate", TODAY.minusMonths(1).toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    private void goal(String name, String target, LocalDate date, UUID place) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "NORMAL", "targetAmount", target,
            "currency", "CRC", "placeIds", List.of(place.toString())));
        if (date != null) {
            body.put("targetDate", date.toString());
        }
        as(a, post(base() + "/goals").content(toJson(body))).andExpect(status().isCreated());
    }

    private static BigDecimal num(Map<String, Object> map, String key) {
        return new BigDecimal(String.valueOf(map.get(key)));
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** V10: metas de ahorro. El avance sale del saldo de sus lugares; nunca se anota dos veces. */
class GoalTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("America/Costa_Rica"));

    TestData.AppUser a;
    TestData.AppUser b;
    UUID supermercado;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
        }
    }

    @Test
    void laMetaAvanzaConElSaldoDeSuLugarYCalculaElPlan() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "CRC", "1000000", TODAY.minusDays(100));
        UUID cdp = place(a, "CDP", "CDP", "CRC", "2250000", TODAY.minusDays(100));
        as(a, post(base(a) + "/transfers").content(toJson(Map.of("from", bcr.toString(), "to", cdp.toString(),
            "date", TODAY.minusDays(30).toString(), "amount", "200000")))).andExpect(status().isCreated());

        LocalDate date = TODAY.withDayOfMonth(1).plusMonths(7);
        Map<String, Object> body = goal("Casarnos", "5000000", date, List.of(cdp));
        body.put("parts", List.of(Map.of("name", "Boda", "amount", "3000000"), Map.of("name", "Luna de miel", "amount", "2000000")));
        Map<String, Object> g = json(as(a, post(base(a) + "/goals").content(toJson(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(num(g, "saved")).isEqualByComparingTo("2450000");
        assertThat(num(g, "remaining")).isEqualByComparingTo("2550000");
        assertThat(((Number) g.get("percent")).intValue()).isEqualTo(49);
        assertThat(num(g, "monthlyNeeded")).isEqualByComparingTo(new BigDecimal("2550000").divide(BigDecimal.valueOf(7), 2, java.math.RoundingMode.UP));
        assertThat(num(g, "monthlyPace")).isEqualByComparingTo("66666.67"); // 200 000 en los últimos 3 meses
        assertThat(g.get("eta")).isNotNull();
        assertThat((List<?>) g.get("parts")).hasSize(2);

        // La cuenta dice para qué meta está apartada; la otra no (ese dinero está disponible).
        List<?> places = JSON.readValue(as(a, get(base(a) + "/places")).andReturn().getResponse().getContentAsString(),
            List.class);
        Map<String, Object> goalOf = new java.util.HashMap<>();
        places.forEach(p -> goalOf.put(String.valueOf(((Map<?, ?>) p).get("id")), ((Map<?, ?>) p).get("goalName")));
        assertThat(goalOf).containsEntry(cdp.toString(), "Casarnos").containsEntry(bcr.toString(), null);
    }

    @Test
    void elFondoDeEmergenciaUsaLosGastosRealesDeLosUltimos3Meses() throws Exception {
        UUID cuenta;
        try (Connection su = asSuperuser()) {
            cuenta = TestData.newPlace(su, a.personalSpace(), a.id());
        }
        for (int i = 1; i <= 3; i++) {
            LocalDate day = TODAY.withDayOfMonth(1).minusMonths(i);
            as(a, post(base(a) + "/transactions").content(toJson(Map.of("categoryId", supermercado.toString(),
                "date", day.toString(), "description", "Súper", "amount", "300000", "currency", "CRC",
                "placeId", cuenta.toString()))))
                .andExpect(status().isCreated());
        }
        Map<String, Object> body = new HashMap<>(Map.of("name", "Fondo de emergencia", "kind", "EMERGENCIA",
            "emergencyMonths", 3, "currency", "CRC"));
        Map<String, Object> g = json(as(a, post(base(a) + "/goals").content(toJson(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(num(g, "monthlyExpenses")).isEqualByComparingTo("300000");
        assertThat(num(g, "target")).isEqualByComparingTo("900000");
    }

    @Test
    void unLugarVaAUnaSolaMetaYDeSuMismaMoneda() throws Exception {
        UUID cdp = place(a, "CDP", "CDP", "CRC", "100", TODAY);
        UUID usd = place(a, "Dólares", "BANCO", "USD", "100", TODAY);
        as(a, post(base(a) + "/goals").content(toJson(goal("Uno", "1000", null, List.of(cdp))))).andExpect(status().isCreated());
        as(a, post(base(a) + "/goals").content(toJson(goal("Dos", "1000", null, List.of(cdp))))).andExpect(status().isConflict());
        as(a, post(base(a) + "/goals").content(toJson(goal("Tres", "1000", null, List.of(usd))))).andExpect(status().isConflict());
        as(a, post(base(a) + "/goals").content(toJson(goal("Uno", "1000", null, List.of())))).andExpect(status().isConflict());
    }

    @Test
    void losHitosSeAvisanUnaVezYAl100LaMetaQuedaCumplida() throws Exception {
        UUID sobre = place(a, "Viaje", "SOBRE", "CRC", "600", TODAY.minusDays(1));
        UUID bank = place(a, "BAC", "BANCO", "CRC", "5000", TODAY.minusDays(1));
        UUID goal = uuid(json(as(a, post(base(a) + "/goals").content(toJson(goal("Viaje", "1000", null, List.of(sobre)))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id"));

        assertThat(milestones(a)).containsExactly(a.id() + "|Viaje|50");
        assertThat(milestones(a)).isEmpty(); // ya se avisó

        as(a, post(base(a) + "/transfers").content(toJson(Map.of("from", bank.toString(), "to", sobre.toString(),
            "date", TODAY.toString(), "amount", "400")))).andExpect(status().isCreated());
        assertThat(milestones(a)).containsExactly(a.id() + "|Viaje|100");
        Map<String, Object> g = goals(a).stream().filter(x -> goal.toString().equals(x.get("id"))).findFirst().orElseThrow();
        assertThat(g).containsEntry("status", "CUMPLIDA");
    }

    @Test
    void bNoVeNiTocaLasMetasDeA() throws Exception {
        UUID cdp = place(a, "CDP", "CDP", "CRC", "100", TODAY);
        UUID goal = uuid(json(as(a, post(base(a) + "/goals").content(toJson(goal("Casa", "1000", null, List.of(cdp)))))
            .andReturn().getResponse().getContentAsString()).get("id"));
        as(b, get(base(a) + "/goals")).andExpect(status().isNotFound());
        as(b, put(base(a) + "/goals/" + goal).content(toJson(goal("Mía", "1", null, List.of())))).andExpect(status().isNotFound());
        as(b, put(base(a) + "/goals/" + goal + "/status").content("{\"status\":\"ARCHIVADA\"}")).andExpect(status().isNotFound());
        as(b, delete(base(a) + "/goals/" + goal)).andExpect(status().isNotFound());
        // B no puede ligar el lugar de A a una meta propia.
        as(b, post(base(b) + "/goals").content(toJson(goal("Robo", "1000", null, List.of(cdp))))).andExpect(status().isNotFound());
        // El orden tiene que incluir todas las metas propias.
        as(a, put(base(a) + "/goals/order").content(toJson(Map.of("ids", List.of())))).andExpect(status().isBadRequest());
    }

    // --- utilidades ----------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static String base(TestData.AppUser user) {
        return "/api/spaces/" + user.personalSpace();
    }

    private UUID place(TestData.AppUser user, String name, String kind, String currency, String opening, LocalDate date)
            throws Exception {
        String body = as(user, post(base(user) + "/places").content(toJson(Map.of("name", name, "kind", kind,
                "currency", currency, "openingBalance", opening, "openingDate", date.toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    private static Map<String, Object> goal(String name, String target, LocalDate date, List<UUID> places) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "NORMAL", "targetAmount", target,
            "currency", "CRC", "placeIds", places.stream().map(UUID::toString).toList()));
        if (date != null) {
            body.put("targetDate", date.toString());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> goals(TestData.AppUser user) throws Exception {
        return JSON.readValue(as(user, get(base(user) + "/goals")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), List.class);
    }

    /** Hitos nuevos (la BD es compartida entre pruebas: solo los de este usuario). */
    private List<String> milestones(TestData.AppUser user) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT user_id, goal_name, milestone FROM casaayg.goal_milestones(?)")) {
                ps.setObject(1, TODAY);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (user.id().toString().equals(rs.getString(1))) {
                            out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3));
                        }
                    }
                }
            }
            app.commit();
        }
        return out;
    }

    private static BigDecimal num(Map<String, Object> map, String key) {
        return new BigDecimal(String.valueOf(map.get(key)));
    }
}

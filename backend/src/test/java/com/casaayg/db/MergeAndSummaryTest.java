package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Fusión de espacios con doble consentimiento (ADR-005) y resumen del mes con tipo de cambio. */
class MergeAndSummaryTest extends WebTestSupport {

    TestData.AppUser pareja1;
    TestData.AppUser pareja2;
    TestData.AppUser tercero;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            pareja1 = TestData.adminCreateUser(su, "G");
            pareja2 = TestData.adminCreateUser(su, "A");
            tercero = TestData.adminCreateUser(su, "T");
            TestData.newTransaction(su, pareja1.personalSpace(), pareja1.id(),
                TestData.categoryNamed(su, pareja1.personalSpace(), "Supermercado"), "1000.00");
            TestData.newTransaction(su, pareja2.personalSpace(), pareja2.id(),
                TestData.categoryNamed(su, pareja2.personalSpace(), "Supermercado"), "2000.00");
        }
    }

    @Test
    void laFusionNecesitaElConsentimientoDeLosDos() throws Exception {
        UUID request = propose(pareja1, pareja2);

        // Quien propuso no puede aprobar por la pareja; un tercero tampoco ve la solicitud.
        as(pareja1, post("/api/merge-requests/" + request + "/approve")).andExpect(status().isNotFound());
        as(tercero, post("/api/merge-requests/" + request + "/approve")).andExpect(status().isNotFound());
        // Aprobar exige passkey reciente.
        as(pareja2, post("/api/merge-requests/" + request + "/approve"), Duration.ofMinutes(10))
            .andExpect(status().isForbidden());

        String approved = as(pareja2, post("/api/merge-requests/" + request + "/approve"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID family = uuid(json(approved).get("familySpaceId"));

        // Ambos ven el espacio familiar con TODOS los movimientos, marcados "antes de la boda".
        for (TestData.AppUser person : List.of(pareja1, pareja2)) {
            String body = as(person, get("/api/spaces/" + family + "/transactions?year=" + now().getYear()
                    + "&month=" + now().getMonthValue()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) json(body).get("items");
            assertThat(items).hasSize(2).allMatch(t -> Boolean.TRUE.equals(t.get("beforeMerge")));
        }
        // Las categorías iguales se unificaron (no hay "Supermercado" dos veces).
        String categories = as(pareja1, get("/api/spaces/" + family + "/categories"))
            .andReturn().getResponse().getContentAsString();
        assertThat(categories.split("\"Supermercado\"", -1)).hasSize(2);
        // Los espacios personales quedaron archivados (solo lectura) y el tercero no ve nada.
        as(pareja1, post("/api/spaces/" + pareja1.personalSpace() + "/categories")
                .content("{\"name\":\"Nueva\",\"group\":\"DESEO\"}"))
            .andExpect(status().isConflict());
        as(tercero, get("/api/spaces/" + family + "/categories")).andExpect(status().isNotFound());
        // No se puede crear un segundo espacio familiar.
        as(tercero, post("/api/merge-requests").content(toJson(Map.of(
                "partnerUsername", pareja1.username(), "familyName", "Otra"))))
            .andExpect(status().isConflict());
    }

    @Test
    void laParejaPuedeRechazarYNadaSeMueve() throws Exception {
        UUID request = propose(pareja1, pareja2);
        as(pareja2, post("/api/merge-requests/" + request + "/decline")).andExpect(status().isNoContent());
        as(pareja2, post("/api/merge-requests/" + request + "/approve")).andExpect(status().isNotFound());
        String spaces = as(pareja1, get("/api/spaces")).andReturn().getResponse().getContentAsString();
        assertThat(spaces).doesNotContain("FAMILIA");
    }

    @Test
    void proponerExigePasskeyRecienteYUnaParejaQueExista() throws Exception {
        Map<String, String> body = Map.of("partnerUsername", pareja2.username(), "familyName", "Casa A&G");
        as(pareja1, post("/api/merge-requests").content(toJson(body)), Duration.ofMinutes(10))
            .andExpect(status().isForbidden());
        as(pareja1, post("/api/merge-requests").content(toJson(Map.of(
                "partnerUsername", "no_existe", "familyName", "X"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void elResumenConvierteLosColonesADolaresConElTipoDeCambio() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 15);
        try (Connection su = asSuperuser()) {
            su.createStatement().execute("""
                INSERT INTO exchange_rate (rate_date, buy, sell, source) VALUES ('2026-10-01', 495.00, 500.00, 'MANUAL')
                ON CONFLICT (rate_date) DO UPDATE SET sell = 500.00, buy = 495.00""");
            UUID space = pareja1.personalSpace();
            UUID salario = TestData.categoryNamed(su, space, "Salario");
            UUID super_ = TestData.categoryNamed(su, space, "Supermercado");
            UUID ahorro = TestData.categoryNamed(su, space, "Ahorro");
            insertTx(su, space, salario, day, "1000000.00", "CRC"); // 2000 USD
            insertTx(su, space, super_, day, "250000.00", "CRC");   // 500 USD
            insertTx(su, space, super_, day, "100.00", "USD");      // 100 USD
            insertTx(su, space, ahorro, day, "100000.00", "CRC");   // 200 USD
        }
        String body = as(pareja1, get("/api/spaces/" + pareja1.personalSpace() + "/summary?year=2026&month=10"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> summary = json(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> expenses = (Map<String, Object>) summary.get("expenses");
        assertThat(new BigDecimal(String.valueOf(expenses.get("crc")))).isEqualByComparingTo("350000");
        assertThat(new BigDecimal(String.valueOf(expenses.get("usd")))).isEqualByComparingTo("100");
        assertThat(new BigDecimal(String.valueOf(expenses.get("totalUsd")))).isEqualByComparingTo("800");
        @SuppressWarnings("unchecked")
        Map<String, Object> percent = (Map<String, Object>) summary.get("percentOfIncome");
        assertThat(new BigDecimal(String.valueOf(percent.get("NECESIDAD")))).isEqualByComparingTo("30.0");
        assertThat(new BigDecimal(String.valueOf(percent.get("AHORRO")))).isEqualByComparingTo("10.0");
        assertThat(summary.get("missingRates")).isEqualTo(false);
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID propose(TestData.AppUser from, TestData.AppUser to) throws Exception {
        String body = as(from, post("/api/merge-requests").content(toJson(Map.of(
                "partnerUsername", to.username(), "familyName", "Casa A&G"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, Duration.ZERO);
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request, Duration ago) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user, ago))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static LocalDate now() {
        return LocalDate.now();
    }

    private static void insertTx(Connection su, UUID space, UUID category, LocalDate day, String amount, String currency)
            throws Exception {
        try (var ps = su.prepareStatement("""
                INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount, currency)
                SELECT ?, m.user_id, ?, ?, 'Prueba', ?::numeric, ? FROM space_member m WHERE m.space_id = ?""")) {
            ps.setObject(1, space);
            ps.setObject(2, category);
            ps.setObject(3, day);
            ps.setString(4, amount);
            ps.setString(5, currency);
            ps.setObject(6, space);
            ps.executeUpdate();
        }
    }
}

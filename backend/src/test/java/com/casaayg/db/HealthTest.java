package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

/**
 * «¿Voy bien?» (PRODUCT.md): las tres partes del dinero, la tasa de ahorro contra el promedio,
 * el fondo de emergencia, las deudas y las metas. Las cuentas se hacen a mano en cada comentario.
 */
class HealthTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Costa_Rica"));
    static final LocalDate M0 = TODAY.withDayOfMonth(1);
    static final LocalDate M1 = M0.minusMonths(1);

    TestData.AppUser ana;
    TestData.AppUser beto;
    UUID banco;
    UUID cdp;
    UUID salario;
    UUID supermercado;
    UUID deudas;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            ana = TestData.adminCreateUser(su, "Ana");
            beto = TestData.adminCreateUser(su, "Beto");
            try (var ps = su.prepareStatement("""
                    INSERT INTO exchange_rate (rate_date, buy, sell, source) VALUES (?, 495.00, 500.00, 'MANUAL')
                    ON CONFLICT (rate_date) DO UPDATE SET sell = 500.00, buy = 495.00""")) {
                ps.setObject(1, M0.minusMonths(4));
                ps.executeUpdate();
            }
            UUID space = ana.personalSpace();
            salario = TestData.categoryNamed(su, space, "Salario");
            supermercado = TestData.categoryNamed(su, space, "Supermercado");
            deudas = TestData.categoryNamed(su, space, "Pago de deudas");
        }
        LocalDate opening = M0.minusMonths(3);
        banco = place("Banco", "BANCO", "CRC", "1000000", opening);
        cdp = place("CDP", "CDP", "CRC", "500000", opening);
        place("Dólares", "BANCO", "USD", "100", opening);
    }

    @Test
    void lasTresPartesYLasCuatroSenales() throws Exception {
        // Mes pasado: entró ₡500 000, se gastó ₡100 000 y se ahorró ₡50 000 (10 %).
        tx(salario, M1.plusDays(4), "500000", null);
        tx(supermercado, M1.plusDays(4), "100000", null);
        transfer(banco, cdp, M1.plusDays(5), "50000");
        // Este mes: entró ₡1 000 000, se gastó ₡50 000 y se ahorró ₡200 000 (20 %).
        tx(salario, TODAY, "1000000", null);
        tx(supermercado, TODAY, "50000", null);
        transfer(banco, cdp, TODAY, "200000");
        budget(supermercado, "200000");

        // Pago fijo de ₡180 000 en la misma categoría: pesa más que lo que queda del presupuesto.
        Map<String, Object> fijo = new HashMap<>(Map.of("name", "Comida fija", "categoryId", supermercado.toString(),
            "currency", "CRC", "notifyDaysBefore", 0, "dueDay", 28, "expectedAmount", "180000"));
        as(ana, post(base() + "/recurring").content(toJson(fijo))).andExpect(status().isCreated());

        // Una deuda con fecha y cuota, sin abonos; otra sin fecha con un abono de ₡10 000.
        Map<String, Object> mama = new HashMap<>(Map.of("creditor", "Mamá", "concept", "Préstamo", "amount", "100000",
            "currency", "CRC", "borrowedOn", TODAY.toString(), "dueDate", M0.plusMonths(2).toString(),
            "installment", "40000", "installmentDay", 28));
        as(ana, post(base() + "/debts").content(toJson(mama))).andExpect(status().isCreated());
        Map<String, Object> tio = new HashMap<>(Map.of("creditor", "Tío", "concept", "Préstamo", "amount", "100000",
            "currency", "CRC", "borrowedOn", TODAY.toString()));
        UUID tioId = UUID.fromString((String) json(as(ana, post(base() + "/debts").content(toJson(tio)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id"));
        tx(deudas, TODAY, "10000", tioId);

        // Fondo de emergencia en el CDP; una meta con fecha y sin ritmo.
        as(ana, post(base() + "/goals").content(toJson(Map.of("name", "Fondo de emergencia", "kind", "EMERGENCIA",
            "emergencyMonths", 3, "currency", "CRC", "placeIds", List.of(cdp.toString())))))
            .andExpect(status().isCreated());
        as(ana, post(base() + "/goals").content(toJson(Map.of("name", "Viaje", "kind", "NORMAL",
            "targetAmount", "1000000", "currency", "CRC", "targetDate", M0.plusMonths(6).toString(),
            "placeIds", List.of())))).andExpect(status().isCreated());

        Map<String, Object> h = health(ana);

        // Banco: 1 000 000 + 500 000 − 100 000 − 50 000 + 1 000 000 − 50 000 − 200 000 − 10 000 = 2 090 000.
        // Dólares: $100 a ₡500 = 50 000. CDP: 500 000 + 50 000 + 200 000 = 750 000.
        Map<?, ?> money = (Map<?, ?>) h.get("money");
        assertThat(num(money, "saved")).isEqualByComparingTo("750000");
        assertThat(num(money, "available")).isEqualByComparingTo("2140000");
        // Súper: max(200 000 − 50 000, 180 000) = 180 000; deudas: la cuota de 40 000.
        assertThat(num(money, "pending")).isEqualByComparingTo("220000");
        assertThat(num(money, "leftover")).isEqualByComparingTo("1920000");
        assertThat(money.get("missingRate")).isEqualTo(false);

        Map<?, ?> rate = (Map<?, ?>) h.get("savingsRate");
        assertThat(rate.get("month")).isEqualTo(20);
        assertThat(rate.get("average")).isEqualTo(10);
        assertThat(rate.get("averageMonths")).isEqualTo(1);

        // Gasto de vida: solo el mes pasado tiene datos, ₡100 000. El CDP cubre 7,5 meses.
        Map<?, ?> emergency = (Map<?, ?>) h.get("emergency");
        assertThat(num(emergency, "monthlyExpenses")).isEqualByComparingTo("100000");
        assertThat(num(emergency, "monthsCovered")).isEqualByComparingTo("7.5");
        assertThat(emergency.get("targetMonths")).isEqualTo(3);

        Map<?, ?> debts = (Map<?, ?>) h.get("debts");
        assertThat(num(debts, "remainingCrc")).isEqualByComparingTo("190000");
        List<?> items = (List<?>) debts.get("items");
        Map<?, ?> mamaH = find(items, "Mamá");
        assertThat(num(mamaH, "monthlyNeeded")).isEqualByComparingTo("50000"); // 100 000 en 2 meses
        assertThat(num(mamaH, "monthlyAverage")).isEqualByComparingTo("0");
        assertThat(mamaH.get("status")).isEqualTo("ATRASADA");
        Map<?, ?> tioH = find(items, "Tío");
        assertThat(num(tioH, "monthlyAverage")).isEqualByComparingTo("10000");
        assertThat(tioH.get("status")).isEqualTo("SIN_FECHA");
        assertThat(tioH.get("estimatedEnd")).isEqualTo(M0.plusMonths(9).toString()); // 90 000 a 10 000 por mes

        Map<?, ?> goals = (Map<?, ?>) h.get("goals");
        assertThat(goals.get("judged")).isEqualTo(1);
        assertThat(goals.get("onTime")).isEqualTo(0);
        assertThat(goals.get("behind")).isEqualTo(List.of("Viaje"));
    }

    @Test
    void sinDatosNoInventaNada() throws Exception {
        Map<String, Object> h = health(ana);
        Map<?, ?> rate = (Map<?, ?>) h.get("savingsRate");
        assertThat(rate.get("month")).isNull();
        assertThat(rate.get("average")).isNull();
        assertThat(h.get("emergency")).isNull();
        assertThat((List<?>) ((Map<?, ?>) h.get("debts")).get("items")).isEmpty();
        assertThat(((Map<?, ?>) h.get("goals")).get("judged")).isEqualTo(0);
    }

    @Test
    void soloLoVeQuienEsDelEspacio() throws Exception {
        as(beto, get(base() + "/health")).andExpect(status().isNotFound());
    }

    // --- utilidades -----------------------------------------------------------------------------

    private Map<String, Object> health(TestData.AppUser user) throws Exception {
        return json(as(user, get(base() + "/health")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString());
    }

    private UUID place(String name, String kind, String currency, String opening, LocalDate date) throws Exception {
        return UUID.fromString((String) json(as(ana, post(base() + "/places").content(toJson(Map.of("name", name,
                "kind", kind, "currency", currency, "openingBalance", opening, "openingDate", date.toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id"));
    }

    private void tx(UUID category, LocalDate day, String amount, UUID debt) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("categoryId", category.toString(), "date", day.toString(),
            "description", "Prueba", "amount", amount, "currency", "CRC", "placeId", banco.toString()));
        if (debt != null) {
            body.put("debtId", debt.toString());
        }
        as(ana, post(base() + "/transactions").content(toJson(body))).andExpect(status().isCreated());
    }

    private void transfer(UUID from, UUID to, LocalDate day, String amount) throws Exception {
        as(ana, post(base() + "/transfers").content(toJson(Map.of("from", from.toString(), "to", to.toString(),
            "date", day.toString(), "amount", amount)))).andExpect(status().isCreated());
    }

    private void budget(UUID category, String amount) throws Exception {
        try (Connection su = asSuperuser()) {
            UUID budget = UUID.randomUUID();
            try (var ps = su.prepareStatement("INSERT INTO budget (id, space_id, created_by, year, month) VALUES (?, ?, ?, ?, ?)")) {
                ps.setObject(1, budget);
                ps.setObject(2, ana.personalSpace());
                ps.setObject(3, ana.id());
                ps.setInt(4, M0.getYear());
                ps.setInt(5, M0.getMonthValue());
                ps.executeUpdate();
            }
            try (var ps = su.prepareStatement(
                    "INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, ?::numeric)")) {
                ps.setObject(1, ana.personalSpace());
                ps.setObject(2, budget);
                ps.setObject(3, category);
                ps.setString(4, amount);
                ps.executeUpdate();
            }
        }
    }

    private String base() {
        return "/api/spaces/" + ana.personalSpace();
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static Map<?, ?> find(List<?> items, String creditor) {
        return items.stream().map(i -> (Map<?, ?>) i).filter(i -> creditor.equals(i.get("creditor"))).findFirst()
            .orElseThrow();
    }

    private static BigDecimal num(Map<?, ?> m, String key) {
        return new BigDecimal(String.valueOf(m.get(key)));
    }
}

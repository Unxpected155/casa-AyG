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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** V7: diezmo ligado a cada ingreso y renovación del CDP con los números de un comprobante real. */
class TitheAndCdpTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now();

    TestData.AppUser a;
    TestData.AppUser b;
    UUID salario;
    UUID supermercado;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            salario = TestData.categoryNamed(su, a.personalSpace(), "Salario");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            // Los usuarios nuevos ya traen Diezmo e Intereses.
            assertThat(TestData.categoryNamed(su, a.personalSpace(), "Diezmo")).isNotNull();
            assertThat(TestData.categoryNamed(su, a.personalSpace(), "Intereses")).isNotNull();
        }
    }

    // --- Diezmo --------------------------------------------------------------------------

    @Test
    void elIngresoConDiezmoAnotaElGastoDel10PorCientoDelBrutoYLoSigue() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "100000", TODAY.minusDays(5));
        Map<String, Object> body = tx(salario, "900000", bcr, "Salario de noviembre");
        body.put("grossAmount", "1000000");
        body.put("tithe", true);
        UUID income = uuid(json(as(a, post(base(a) + "/transactions").content(toJson(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id"));

        Map<String, Object> tithe = titheOf(income);
        assertThat(new BigDecimal(String.valueOf(tithe.get("amount")))).isEqualByComparingTo("100000");
        assertThat(tithe).containsEntry("placeId", bcr.toString()).containsEntry("date", TODAY.toString());
        assertThat(balance(a, bcr)).isEqualByComparingTo("900000"); // 100 000 + 900 000 − 100 000

        // Cambia el bruto: el diezmo se ajusta solo.
        body.put("grossAmount", "1100000");
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(new BigDecimal(String.valueOf(titheOf(income).get("amount")))).isEqualByComparingTo("110000");

        // Desmarcar la casilla quita el diezmo; volver a marcarla lo agrega.
        body.put("tithe", false);
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(titheOf(income)).isNull();
        body.put("tithe", true);
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(titheOf(income)).isNotNull();

        // Borrar el ingreso borra su diezmo.
        as(a, delete(base(a) + "/transactions/" + income)).andExpect(status().isNoContent());
        assertThat(transactions(a)).isEmpty();
        assertThat(balance(a, bcr)).isEqualByComparingTo("100000");
    }

    @Test
    void sinBrutoElDiezmoSaleDelMontoYSoloLosIngresosLlevanDiezmo() throws Exception {
        Map<String, Object> body = tx(salario, "50000", null, "Venta de bicicleta");
        body.put("tithe", true);
        UUID income = uuid(json(as(a, post(base(a) + "/transactions").content(toJson(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id"));
        assertThat(new BigDecimal(String.valueOf(titheOf(income).get("amount")))).isEqualByComparingTo("5000");

        Map<String, Object> expense = tx(supermercado, "1000", null, "Pan");
        expense.put("tithe", true);
        as(a, post(base(a) + "/transactions").content(toJson(expense))).andExpect(status().isBadRequest());
        expense.remove("tithe");
        expense.put("grossAmount", "2000");
        as(a, post(base(a) + "/transactions").content(toJson(expense))).andExpect(status().isBadRequest());
    }

    @Test
    void sugiereElUltimoSalarioBruto() throws Exception {
        Map<String, Object> body = tx(salario, "900000", null, "Salario");
        body.put("grossAmount", "1000000");
        as(a, post(base(a) + "/transactions").content(toJson(body))).andExpect(status().isCreated());
        String s = as(a, get(base(a) + "/transactions/suggest?categoryId=" + salario))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(String.valueOf(json(s).get("lastGross")))).isEqualByComparingTo("1000000");
    }

    // --- CDP -----------------------------------------------------------------------------

    @Test
    void laRenovacionCuadraConElComprobanteDelBanco() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "500000", TODAY.minusDays(30));
        UUID cdp = place(a, "CDP ahorro", "CDP", "2250000", TODAY.minusDays(30));
        configure(a, cdp, bcr, TODAY).andExpect(status().isOk());

        Map<String, Object> preview = json(as(a, get(base(a) + "/places/" + cdp + "/cdp/preview"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(num(preview, "grossInterest")).isEqualByComparingTo("5343.75");
        assertThat(num(preview, "tax")).isEqualByComparingTo("801.55");
        assertThat(num(preview, "netInterest")).isEqualByComparingTo("4542.20");
        assertThat(num(preview, "tithe")).isEqualByComparingTo("534.38");
        assertThat(num(preview, "newPrincipal")).isEqualByComparingTo("2450000");

        as(a, post(base(a) + "/places/" + cdp + "/cdp/renew").content("{\"tithe\":true}"))
            .andExpect(status().isOk());
        assertThat(balance(a, bcr)).isEqualByComparingTo("304007.82"); // 500 000 + 4 542,20 − 534,38 − 200 000
        assertThat(balance(a, cdp)).isEqualByComparingTo("2450000");

        // El vencimiento pasó al siguiente plazo: no se puede renovar dos veces.
        as(a, post(base(a) + "/places/" + cdp + "/cdp/renew").content("{\"tithe\":true}"))
            .andExpect(status().isConflict());
        Map<String, Object> place = places(a).stream().filter(p -> cdp.toString().equals(p.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) place.get("cdp");
        assertThat(data).containsEntry("maturesOn", TODAY.plusDays(30).toString());

        // El mes siguiente el interés se calcula con el capital nuevo: 2 450 000 → ₡5 818,75.
        Map<String, Object> next = json(as(a, get(base(a) + "/places/" + cdp + "/cdp/preview"))
            .andReturn().getResponse().getContentAsString());
        assertThat(num(next, "grossInterest")).isEqualByComparingTo("5818.75");
    }

    @Test
    void noSeRenuevaAntesDeVencerNiConUnaCuentaDeOtraMoneda() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "0", TODAY);
        UUID dolares = place(a, "Dólares", "BANCO", "0", TODAY, "USD");
        UUID cdp = place(a, "CDP", "CDP", "1000", TODAY);
        configure(a, cdp, dolares, TODAY).andExpect(status().isConflict());
        configure(a, cdp, bcr, TODAY.plusDays(3)).andExpect(status().isOk());
        as(a, post(base(a) + "/places/" + cdp + "/cdp/renew").content("{}")).andExpect(status().isConflict());
        // Un banco no se configura como CDP.
        configure(a, bcr, cdp, TODAY).andExpect(status().isBadRequest());
    }

    @Test
    void bNoVeNiRenuevaElCdpDeA() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "0", TODAY);
        UUID cdp = place(a, "CDP", "CDP", "1000", TODAY);
        configure(a, cdp, bcr, TODAY).andExpect(status().isOk());
        as(b, get(base(a) + "/places/" + cdp + "/cdp/preview")).andExpect(status().isNotFound());
        as(b, post(base(a) + "/places/" + cdp + "/cdp/renew").content("{}")).andExpect(status().isNotFound());
        configure(b, cdp, bcr, TODAY).andExpect(status().isNotFound());
        // Desde su propio espacio tampoco: el CDP de A no existe para B.
        as(b, post(base(b) + "/places/" + cdp + "/cdp/renew").content("{}")).andExpect(status().isNotFound());
    }

    // --- utilidades ----------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static String base(TestData.AppUser user) {
        return "/api/spaces/" + user.personalSpace();
    }

    private UUID place(TestData.AppUser user, String name, String kind, String opening, LocalDate date) throws Exception {
        return place(user, name, kind, opening, date, "CRC");
    }

    private UUID place(TestData.AppUser user, String name, String kind, String opening, LocalDate date, String currency)
            throws Exception {
        String body = as(user, post(base(user) + "/places").content(toJson(Map.of("name", name, "kind", kind,
                "currency", currency, "openingBalance", opening, "openingDate", date.toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    private ResultActions configure(TestData.AppUser user, UUID cdp, UUID payout, LocalDate maturesOn) throws Exception {
        return as(user, put(base(user) + "/places/" + cdp + "/cdp").content(toJson(Map.of("rate", "2.85",
            "termDays", 30, "dayBasis", 360, "taxPct", "15", "maturesOn", maturesOn.toString(),
            "payoutPlace", payout.toString(), "lastContribution", "200000"))));
    }

    private static Map<String, Object> tx(UUID category, String amount, UUID place, String description) {
        Map<String, Object> body = new HashMap<>(Map.of("categoryId", category.toString(), "date", TODAY.toString(),
            "description", description, "amount", amount, "currency", "CRC"));
        if (place != null) {
            body.put("placeId", place.toString());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> transactions(TestData.AppUser user) throws Exception {
        String body = as(user, get(base(user) + "/transactions?year=" + TODAY.getYear() + "&month=" + TODAY.getMonthValue()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return (List<Map<String, Object>>) json(body).get("items");
    }

    private Map<String, Object> titheOf(UUID income) throws Exception {
        return transactions(a).stream().filter(t -> income.toString().equals(t.get("titheOf"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> places(TestData.AppUser user) throws Exception {
        return JSON.readValue(as(user, get(base(user) + "/places")).andReturn().getResponse().getContentAsString(), List.class);
    }

    private BigDecimal balance(TestData.AppUser user, UUID place) throws Exception {
        return places(user).stream().filter(p -> place.toString().equals(p.get("id")))
            .map(p -> new BigDecimal(String.valueOf(p.get("balance")))).findFirst().orElseThrow();
    }

    private static BigDecimal num(Map<String, Object> map, String key) {
        return new BigDecimal(String.valueOf(map.get(key)));
    }
}

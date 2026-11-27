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

/** Diezmo por dar (V25, antes V7) y renovación del CDP con los números de un comprobante real. */
class TitheAndCdpTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now();

    TestData.AppUser a;
    TestData.AppUser b;
    UUID salario;
    UUID supermercado;
    UUID diezmo;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            salario = TestData.categoryNamed(su, a.personalSpace(), "Salario");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            diezmo = TestData.categoryNamed(su, a.personalSpace(), "Diezmo");
            // Los usuarios nuevos ya traen Diezmo e Intereses.
            assertThat(TestData.categoryNamed(su, a.personalSpace(), "Diezmo")).isNotNull();
            assertThat(TestData.categoryNamed(su, a.personalSpace(), "Intereses")).isNotNull();
        }
    }

    // --- Diezmo --------------------------------------------------------------------------

    @Test
    void elIngresoConDiezmoDejaPendienteEl10PorCientoDelBrutoSinAnotarElGasto() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "100000", TODAY.minusDays(5));
        Map<String, Object> body = tx(salario, "900000", bcr, "Salario de noviembre");
        body.put("grossAmount", "1000000");
        body.put("tithe", true);
        Map<String, Object> created = json(as(a, post(base(a) + "/transactions").content(toJson(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID income = uuid(created.get("id"));

        // V25: no se anota el gasto; el diezmo queda por dar.
        assertThat(created).containsEntry("titheOwed", true);
        assertThat(titheOf(income)).isNull();
        assertThat(balance(a, bcr)).isEqualByComparingTo("1000000"); // 100 000 + 900 000
        assertThat(pending("CRC")).isEqualByComparingTo("100000");

        // Cambia el bruto: lo pendiente se ajusta.
        body.put("grossAmount", "1100000");
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(pending("CRC")).isEqualByComparingTo("110000");

        // Dar parte del diezmo (un gasto en «Diezmo») lo baja.
        as(a, post(base(a) + "/transactions").content(toJson(tx(diezmo, "60000", bcr, "Diezmo"))))
            .andExpect(status().isCreated());
        assertThat(pending("CRC")).isEqualByComparingTo("50000");

        // Desmarcar la casilla: ese ingreso ya no debe diezmo.
        body.put("tithe", false);
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(pending("CRC")).isNull();
    }

    @Test
    void loPendienteSeArrastraYUnDiezmoDadoAntesNoLoTapa() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "500000", TODAY.minusMonths(3));
        // Un diezmo dado antes del primer ingreso marcado (de antes de usar la app) no cuenta.
        Map<String, Object> viejo = tx(diezmo, "30000", bcr, "Diezmo viejo");
        viejo.put("date", TODAY.minusMonths(2).toString());
        as(a, post(base(a) + "/transactions").content(toJson(viejo))).andExpect(status().isCreated());

        // Ingreso del mes pasado con diezmo, sin darlo: sigue pendiente este mes.
        Map<String, Object> pasado = tx(salario, "200000", bcr, "Salario de octubre");
        pasado.put("date", TODAY.minusMonths(1).toString());
        pasado.put("tithe", true);
        as(a, post(base(a) + "/transactions").content(toJson(pasado))).andExpect(status().isCreated());
        // Y uno de este mes.
        Map<String, Object> ahora = tx(salario, "300000", bcr, "Salario de noviembre");
        ahora.put("tithe", true);
        as(a, post(base(a) + "/transactions").content(toJson(ahora))).andExpect(status().isCreated());
        assertThat(pending("CRC")).isEqualByComparingTo("50000"); // 20 000 + 30 000

        // Dar de más no deja saldo a favor.
        as(a, post(base(a) + "/transactions").content(toJson(tx(diezmo, "80000", bcr, "Diezmo"))))
            .andExpect(status().isCreated());
        assertThat(pending("CRC")).isEqualByComparingTo("0");
    }

    @Test
    void losIngresosDeAntesConSuDiezmoAnotadoQuedanAlDia() throws Exception {
        UUID bcr = place(a, "BCR", "BANCO", "0", TODAY.minusDays(5));
        UUID income = UUID.randomUUID();
        try (Connection su = asSuperuser()) {
            // Como lo dejaba V7: el ingreso y su gasto de diezmo ligado, ya migrados (tithe_owed).
            try (var ps = su.prepareStatement("""
                    INSERT INTO transaction (id, space_id, created_by, category_id, tx_date, description, amount,
                                             currency, place_id, gross_amount, tithe_owed)
                    VALUES (?, ?, ?, ?, ?, 'Salario', 900000, 'CRC', ?, 1000000, true)""")) {
                ps.setObject(1, income);
                ps.setObject(2, a.personalSpace());
                ps.setObject(3, a.id());
                ps.setObject(4, salario);
                ps.setObject(5, TODAY);
                ps.setObject(6, bcr);
                ps.executeUpdate();
            }
            try (var ps = su.prepareStatement("""
                    INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount,
                                             currency, place_id, tithe_of)
                    VALUES (?, ?, ?, ?, 'Diezmo: Salario', 100000, 'CRC', ?, ?)""")) {
                ps.setObject(1, a.personalSpace());
                ps.setObject(2, a.id());
                ps.setObject(3, diezmo);
                ps.setObject(4, TODAY);
                ps.setObject(5, bcr);
                ps.setObject(6, income);
                ps.executeUpdate();
            }
        }
        assertThat(pending("CRC")).isEqualByComparingTo("0");

        // Editarlo sigue ajustando su gasto ligado (y quitarle el diezmo lo borra).
        Map<String, Object> body = tx(salario, "900000", bcr, "Salario");
        body.put("grossAmount", "1200000");
        body.put("tithe", true);
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(new BigDecimal(String.valueOf(titheOf(income).get("amount")))).isEqualByComparingTo("120000");
        assertThat(pending("CRC")).isEqualByComparingTo("0");
        body.put("tithe", false);
        as(a, put(base(a) + "/transactions/" + income).content(toJson(body))).andExpect(status().isOk());
        assertThat(titheOf(income)).isNull();
    }

    @Test
    void sinBrutoElDiezmoSaleDelMontoYSoloLosIngresosLlevanDiezmo() throws Exception {
        Map<String, Object> body = tx(salario, "50000", cuenta(), "Venta de bicicleta");
        body.put("tithe", true);
        as(a, post(base(a) + "/transactions").content(toJson(body))).andExpect(status().isCreated());
        assertThat(pending("CRC")).isEqualByComparingTo("5000");

        Map<String, Object> expense = tx(supermercado, "1000", cuenta(), "Pan");
        expense.put("tithe", true);
        as(a, post(base(a) + "/transactions").content(toJson(expense))).andExpect(status().isBadRequest());
        expense.remove("tithe");
        expense.put("grossAmount", "2000");
        as(a, post(base(a) + "/transactions").content(toJson(expense))).andExpect(status().isBadRequest());
    }

    @Test
    void sugiereElUltimoSalarioBruto() throws Exception {
        Map<String, Object> body = tx(salario, "900000", cuenta(), "Salario");
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
        // El diezmo de los intereses no sale solo de la cuenta: queda por dar (V25).
        assertThat(balance(a, bcr)).isEqualByComparingTo("304542.20"); // 500 000 + 4 542,20 − 200 000
        assertThat(pending("CRC")).isEqualByComparingTo("534.38");
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

    private UUID cuenta() throws Exception {
        try (Connection su = asSuperuser()) {
            return TestData.newPlace(su, a.personalSpace(), a.id());
        }
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

    /** Lo que falta dar en esa moneda («¿Voy bien?»), o null si no hay nada que deba diezmo. */
    private BigDecimal pending(String currency) throws Exception {
        Map<String, Object> health = json(as(a, get(base(a) + "/health")).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString());
        return ((List<?>) health.get("tithe")).stream().map(t -> (Map<?, ?>) t)
            .filter(t -> currency.equals(t.get("currency"))).findFirst()
            .map(t -> new BigDecimal(String.valueOf(t.get("pending")))).orElse(null);
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

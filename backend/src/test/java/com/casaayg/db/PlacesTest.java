package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** V6: lugares, saldo, dinero movido y sugerencias. A y B no comparten espacio. */
class PlacesTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now();

    TestData.AppUser a;
    TestData.AppUser b;
    UUID supermercado;
    UUID salario;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            salario = TestData.categoryNamed(su, a.personalSpace(), "Salario");
        }
    }

    @Test
    void elSaldoSumaIngresosRestaGastosYCuentaElDineroMovido() throws Exception {
        UUID banco = place(a, "BAC colones", "BANCO", "CRC", "100000", TODAY.minusDays(10), null);
        UUID ahorro = place(a, "Ahorros", "BANCO", "CRC", "0", TODAY.minusDays(10), null);

        tx(a, salario, "50000", "CRC", TODAY.minusDays(2), banco, "Salario");
        tx(a, supermercado, "20000", "CRC", TODAY.minusDays(1), banco, "Automercado");
        // No cuentan: sin lugar, antes del saldo inicial y con fecha futura.
        tx(a, supermercado, "999", "CRC", TODAY, null, "Sin lugar");
        tx(a, supermercado, "777", "CRC", TODAY.minusDays(20), banco, "Antes del saldo inicial");
        tx(a, supermercado, "555", "CRC", TODAY.plusDays(3), banco, "Futuro");
        as(a, post(base(a) + "/transfers").content(toJson(Map.of(
                "from", banco.toString(), "to", ahorro.toString(), "date", TODAY.toString(), "amount", "30000"))))
            .andExpect(status().isCreated());

        Map<UUID, BigDecimal> balances = balances(a);
        assertThat(balances.get(banco)).isEqualByComparingTo("100000");
        assertThat(balances.get(ahorro)).isEqualByComparingTo("30000");
    }

    @Test
    void laMonedaDelMovimientoYDeLaTransferenciaTieneQueSerLaDelLugar() throws Exception {
        UUID colones = place(a, "Colones", "BANCO", "CRC", "1000", TODAY, null);
        UUID dolares = place(a, "Dólares", "BANCO", "USD", "100", TODAY, null);

        as(a, post(base(a) + "/transactions").content(toJson(txBody(supermercado, "10", "USD", TODAY, colones, "x"))))
            .andExpect(status().isConflict());
        as(a, post(base(a) + "/transfers").content(toJson(Map.of(
                "from", colones.toString(), "to", dolares.toString(), "date", TODAY.toString(), "amount", "10"))))
            .andExpect(status().isConflict());

        // La BD lo rechaza aunque la app se equivocara (trigger de V6).
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, a.id());
            assertThatThrownBy(() -> {
                try (var ps = app.prepareStatement("""
                        INSERT INTO casaayg.transfer (space_id, created_by, from_place, to_place, tx_date, amount)
                        VALUES (?, ?, ?, ?, current_date, 1)""")) {
                    ps.setObject(1, a.personalSpace());
                    ps.setObject(2, a.id());
                    ps.setObject(3, colones);
                    ps.setObject(4, dolares);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("misma moneda");
        }
    }

    @Test
    void bNoVeNiUsaLosLugaresDeA() throws Exception {
        UUID banco = place(a, "BAC", "BANCO", "CRC", "1000", TODAY, null);
        UUID otro = place(a, "Otro", "EFECTIVO", "CRC", "0", TODAY, null);
        String created = as(a, post(base(a) + "/transfers").content(toJson(Map.of(
                "from", banco.toString(), "to", otro.toString(), "date", TODAY.toString(), "amount", "5"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID transfer = uuid(json(created).get("id"));
        UUID categoryB;
        try (Connection su = asSuperuser()) {
            categoryB = TestData.categoryNamed(su, b.personalSpace(), "Supermercado");
        }

        as(b, get(base(a) + "/places")).andExpect(status().isNotFound());
        as(b, put(base(a) + "/places/" + banco).content(toJson(Map.of(
                "name", "Mío", "openingBalance", "0", "openingDate", TODAY.toString(), "archived", false))))
            .andExpect(status().isNotFound());
        as(b, delete(base(a) + "/transfers/" + transfer)).andExpect(status().isNotFound());
        // Usar el lugar de A desde el espacio de B: 404, igual que si no existiera.
        as(b, post(base(b) + "/transactions").content(toJson(txBody(categoryB, "1", "CRC", TODAY, banco, "x"))))
            .andExpect(status().isNotFound());
        as(b, post(base(b) + "/transfers").content(toJson(Map.of(
                "from", banco.toString(), "to", otro.toString(), "date", TODAY.toString(), "amount", "1"))))
            .andExpect(status().isNotFound());

        // RLS: aunque la app olvidara el filtro, la BD no le muestra nada a B.
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, b.id());
            try (var ps = app.prepareStatement("""
                    SELECT (SELECT count(*) FROM casaayg.place WHERE space_id = ?)
                         + (SELECT count(*) FROM casaayg.transfer WHERE space_id = ?)""")) {
                ps.setObject(1, a.personalSpace());
                ps.setObject(2, a.personalSpace());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
            }
        }
        assertThat(balances(a)).containsKey(banco);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spaceId", "createdBy", "balance", "id"})
    void camposQueNoSeDebenMandarSeRechazan(String extra) throws Exception {
        Map<String, Object> body = new HashMap<>(placeBody("X", "BANCO", "CRC", "0", TODAY, null));
        body.put(extra, b.id().toString());
        as(a, post(base(a) + "/places").content(toJson(body))).andExpect(status().isBadRequest());
    }

    @Test
    void repartirMueveATodosLosSobresJuntosYSoloUnSobreVaDentroDeOtroLugar() throws Exception {
        UUID banco = place(a, "BAC", "BANCO", "CRC", "100000", TODAY, null);
        UUID comida = place(a, "Comida", "SOBRE", "CRC", "0", TODAY, banco);
        UUID viaje = place(a, "Viaje", "SOBRE", "CRC", "0", TODAY, banco);

        // Un banco no va dentro de otro lugar; un sobre no va dentro de otro sobre.
        as(a, post(base(a) + "/places").content(toJson(placeBody("Banco hijo", "BANCO", "CRC", "0", TODAY, banco))))
            .andExpect(status().isBadRequest());
        as(a, post(base(a) + "/places").content(toJson(placeBody("Sub", "SOBRE", "CRC", "0", TODAY, comida))))
            .andExpect(status().isBadRequest());

        Map<String, Object> repeated = Map.of("from", banco.toString(), "date", TODAY.toString(), "items", List.of(
            Map.of("to", comida.toString(), "amount", "1"), Map.of("to", comida.toString(), "amount", "2")));
        as(a, post(base(a) + "/transfers/distribute").content(toJson(repeated))).andExpect(status().isBadRequest());

        Map<String, Object> ok = Map.of("from", banco.toString(), "date", TODAY.toString(),
            "description", "Pago de noviembre", "items", List.of(
                Map.of("to", comida.toString(), "amount", "80000"), Map.of("to", viaje.toString(), "amount", "15000")));
        as(a, post(base(a) + "/transfers/distribute").content(toJson(ok))).andExpect(status().isCreated());

        Map<UUID, BigDecimal> balances = balances(a);
        assertThat(balances.get(banco)).isEqualByComparingTo("5000");
        assertThat(balances.get(comida)).isEqualByComparingTo("80000");
        assertThat(balances.get(viaje)).isEqualByComparingTo("15000");
        // El sobre que falló no dejó nada a medias: todo o nada.
        assertThat(balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100000");
    }

    @Test
    void sugiereLaCategoriaDeAntesYElUltimoLugar() throws Exception {
        UUID banco = place(a, "BAC", "BANCO", "CRC", "0", TODAY, null);
        tx(a, supermercado, "100", "CRC", TODAY, banco, "Automercado Escazú");

        String body = as(a, get(base(a) + "/transactions/suggest?description=automer"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(body)).containsEntry("categoryId", supermercado.toString()).containsEntry("placeId", banco.toString());
        // Un comodín de LIKE no se cuela: "%" no coincide con todo.
        String wildcard = as(a, get(base(a) + "/transactions/suggest?description=%25%25"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(wildcard).get("categoryId")).isNull();
        // B no puede pedir sugerencias del espacio de A.
        as(b, get(base(a) + "/transactions/suggest?description=automer")).andExpect(status().isNotFound());
    }

    @Test
    void laFusionNoMueveLosLugaresPersonales() throws Exception {
        UUID banco = place(a, "BAC", "BANCO", "CRC", "1000", TODAY.minusDays(1), null);
        tx(a, supermercado, "300", "CRC", TODAY, banco, "Súper");
        place(b, "BAC", "BANCO", "CRC", "500", TODAY.minusDays(1), null);

        String proposed = as(a, post("/api/merge-requests").content(toJson(Map.of(
                "partnerUsername", b.username(), "familyName", "Casa"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String approved = as(b, post("/api/merge-requests/" + json(proposed).get("id") + "/approve"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID family = uuid(json(approved).get("familySpaceId"));

        // V11: la casa empieza sin lugares; los personales siguen donde estaban, con su saldo.
        String body = as(a, get("/api/spaces/" + family + "/places"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list(body)).isEmpty();
        assertThat(balances(a).get(banco)).isEqualByComparingTo("700");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE casaayg.place SET currency = 'USD'",
        "UPDATE casaayg.place SET kind = 'CDP'",
        "UPDATE casaayg.place SET space_id = space_id",
        "UPDATE casaayg.place SET created_by = created_by",
        "UPDATE casaayg.transfer SET amount = 1",
        "DELETE FROM casaayg.place"})
    void laAppNoPuedeCambiarLoQueDefineUnLugarNiEditarTransferencias(String sql) throws Exception {
        place(a, "BAC", "BANCO", "CRC", "0", TODAY, null);
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, a.id());
            assertThatThrownBy(() -> app.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    // --- utilidades ----------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static String base(TestData.AppUser user) {
        return "/api/spaces/" + user.personalSpace();
    }

    private UUID place(TestData.AppUser user, String name, String kind, String currency, String opening,
                       LocalDate date, UUID parent) throws Exception {
        String body = as(user, post(base(user) + "/places")
                .content(toJson(placeBody(name, kind, currency, opening, date, parent))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    private static Map<String, Object> placeBody(String name, String kind, String currency, String opening,
                                                 LocalDate date, UUID parent) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", kind, "currency", currency,
            "openingBalance", opening, "openingDate", date.toString()));
        if (parent != null) {
            body.put("parentId", parent.toString());
        }
        return body;
    }

    private void tx(TestData.AppUser user, UUID category, String amount, String currency, LocalDate date, UUID place,
                    String description) throws Exception {
        as(user, post(base(user) + "/transactions")
                .content(toJson(txBody(category, amount, currency, date, place, description))))
            .andExpect(status().isCreated());
    }

    private static Map<String, Object> txBody(UUID category, String amount, String currency, LocalDate date,
                                              UUID place, String description) {
        Map<String, Object> body = new HashMap<>(Map.of("categoryId", category.toString(), "date", date.toString(),
            "description", description, "amount", amount, "currency", currency));
        if (place != null) {
            body.put("placeId", place.toString());
        }
        return body;
    }

    private Map<UUID, BigDecimal> balances(TestData.AppUser user) throws Exception {
        String body = as(user, get(base(user) + "/places"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> p : list(body)) {
            result.put(uuid(p.get("id")), new BigDecimal(String.valueOf(p.get("balance"))));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(String body) {
        return JSON.readValue(body, List.class);
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

/** V18: ahorrar es mover dinero a un CDP o a una meta, no un gasto. */
class SavingsTest extends WebTestSupport {

    static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    TestData.AppUser ana;
    UUID banco;
    UUID cdp;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            ana = TestData.adminCreateUser(su, "Ana");
            su.createStatement().execute("""
                INSERT INTO exchange_rate (rate_date, buy, sell, source) VALUES ('2026-09-01', 495.00, 500.00, 'MANUAL')
                ON CONFLICT (rate_date) DO UPDATE SET sell = 500.00, buy = 495.00""");
            UUID space = ana.personalSpace();
            banco = place(su, space, "Banco", "BANCO");
            cdp = place(su, space, "CDP", "CDP");
            tx(su, space, TestData.categoryNamed(su, space, "Salario"), "1000000");
            tx(su, space, TestData.categoryNamed(su, space, "Supermercado"), "300000");
        }
    }

    @Test
    void moverAlCdpEsAhorroYNoGasto() throws Exception {
        transfer(banco, cdp, "200000");
        Map<String, Object> s = summary();
        assertThat(num(s, "expenses", "crc")).isEqualByComparingTo("300000");
        assertThat(num(s, "saved", "crc")).isEqualByComparingTo("200000");
        assertThat(num(s, "percentOfIncome", "AHORRO")).isEqualByComparingTo("20.0");
        assertThat(num(s, "percentOfIncome", "NECESIDAD")).isEqualByComparingTo("30.0");
    }

    @Test
    void elPresupuestoDeAhorroSeCumpleConLaTransferencia() throws Exception {
        try (Connection su = asSuperuser()) {
            UUID space = ana.personalSpace();
            UUID ahorro = TestData.newCategory(su, space, ana.id(), "Ahorro", "AHORRO");
            UUID budget = UUID.randomUUID();
            try (var ps = su.prepareStatement("INSERT INTO budget (id, space_id, created_by, year, month) VALUES (?, ?, ?, 2026, 9)")) {
                ps.setObject(1, budget);
                ps.setObject(2, space);
                ps.setObject(3, ana.id());
                ps.executeUpdate();
            }
            try (var ps = su.prepareStatement(
                    "INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, 200000)")) {
                ps.setObject(1, space);
                ps.setObject(2, budget);
                ps.setObject(3, ahorro);
                ps.executeUpdate();
            }
        }
        transfer(banco, cdp, "200000");
        Map<String, Object> s = summary();
        Map<?, ?> line = ((java.util.List<?>) s.get("categories")).stream().map(l -> (Map<?, ?>) l)
            .filter(l -> "Ahorro".equals(l.get("name"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(String.valueOf(line.get("spentCrc")))).isEqualByComparingTo("200000");
        assertThat(line.get("status")).isEqualTo("AL_LIMITE");
        // Pero no es gasto, ni se cuenta dos veces en el %.
        assertThat(num(s, "expenses", "crc")).isEqualByComparingTo("300000");
        assertThat(num(s, "percentOfIncome", "AHORRO")).isEqualByComparingTo("20.0");
    }

    @Test
    void renovarElCdpCuentaSoloElAporte() throws Exception {
        // Vence: el capital vuelve al banco y sale otra vez con 150 000 más.
        transfer(cdp, banco, "500000");
        transfer(banco, cdp, "650000");
        assertThat(num(summary(), "saved", "crc")).isEqualByComparingTo("150000");
    }

    @Test
    void lasNuevasCuentasNoTraenAhorroComoGasto() throws Exception {
        try (Connection su = asSuperuser(); var ps = su.prepareStatement("""
                SELECT string_agg(name || ':' || category_group || ':' || active, ',' ORDER BY name)
                  FROM category WHERE space_id = ? AND name IN ('Ahorro', 'Inversiones', 'Pago de deudas')""")) {
            ps.setObject(1, ana.personalSpace());
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("Pago de deudas:NECESIDAD:true");
            }
        }
    }

    @Test
    void elFondoDeEmergenciaNoCuentaElAhorroComoGastoDeVida() throws Exception {
        try (Connection su = asSuperuser()) {
            UUID space = ana.personalSpace();
            UUID ahorro = TestData.newCategory(su, space, ana.id(), "Ahorro viejo", "AHORRO");
            tx(su, space, ahorro, "900000");
            try (var ps = su.prepareStatement("SELECT monthly_expenses(?, 'CRC', DATE '2026-10-05')")) {
                ps.setObject(1, space);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    // V24: solo septiembre tiene datos, así que se divide entre 1 (antes, entre 3).
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("300000");
                }
            }
        }
    }

    @Test
    void elFondoConvierteLosDolaresYPromediaSoloLosMesesConDatos() throws Exception {
        try (Connection su = asSuperuser()) {
            UUID space = ana.personalSpace();
            UUID super_ = TestData.categoryNamed(su, space, "Supermercado");
            // Agosto: ₡100 000 más $100 (a ₡500). Septiembre ya tiene ₡300 000 (seed).
            insertTx(su, space, super_, "2026-08-05", "100000", "CRC", null);
            insertTx(su, space, super_, "2026-08-06", "100", "USD", null);
            su.createStatement().execute("""
                INSERT INTO exchange_rate (rate_date, buy, sell, source) VALUES ('2026-08-01', 495.00, 500.00, 'MANUAL')
                ON CONFLICT (rate_date) DO UPDATE SET sell = 500.00, buy = 495.00""");
            // (300 000 + 100 000 + 50 000) / 2 meses con datos; julio no tiene nada y no cuenta.
            assertThat(scalar(su, "SELECT monthly_expenses(?, 'CRC', DATE '2026-10-05')", space)).isEqualByComparingTo("225000");
            assertThat(scalar(su, "SELECT monthly_expenses(?, 'USD', DATE '2026-10-05')", space)).isEqualByComparingTo("450");
        }
    }

    @Test
    void sinMesesConDatosElFondoUsaElPresupuesto() throws Exception {
        try (Connection su = asSuperuser()) {
            UUID space = ana.personalSpace();
            UUID budget = UUID.randomUUID();
            try (var ps = su.prepareStatement("INSERT INTO budget (id, space_id, created_by, year, month) VALUES (?, ?, ?, 2026, 9)")) {
                ps.setObject(1, budget);
                ps.setObject(2, space);
                ps.setObject(3, ana.id());
                ps.executeUpdate();
            }
            try (var ps = su.prepareStatement(
                    "INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, 400000)")) {
                ps.setObject(1, space);
                ps.setObject(2, budget);
                ps.setObject(3, TestData.categoryNamed(su, space, "Supermercado"));
                ps.executeUpdate();
            }
            // Junio a agosto no tienen nada: se usa lo planeado en septiembre.
            assertThat(scalar(su, "SELECT monthly_expenses(?, 'CRC', DATE '2026-09-20')", space)).isEqualByComparingTo("400000");
        }
    }

    @Test
    void loAhorradoNetoCuentaIngresosDirectosYRetiros() throws Exception {
        transfer(banco, cdp, "200000");
        try (Connection su = asSuperuser()) {
            UUID space = ana.personalSpace();
            // Un ingreso que entra directo al CDP suma; un gasto pagado desde el CDP resta.
            insertTx(su, space, TestData.categoryNamed(su, space, "Salario"), "2026-09-12", "50000", "CRC", cdp);
            insertTx(su, space, TestData.categoryNamed(su, space, "Supermercado"), "2026-09-13", "30000", "CRC", cdp);
            assertThat(scalar(su, "SELECT period_savings_net_crc(?, DATE '2026-09-01', DATE '2026-10-01')", space))
                .isEqualByComparingTo("220000");
        }
    }

    @Test
    void laMarcaDeAhorroDecideQueEsApartado() throws Exception {
        UUID space = ana.personalSpace();
        Map<String, Object> sobre = newPlace(space, "Viaje", "SOBRE");
        assertThat(sobre).containsEntry("savings", false);
        assertThat(newPlace(space, "CDP 2", "CDP")).containsEntry("savings", true);

        // Mover a un sobre común no es ahorro; marcarlo como de ahorro, sí.
        UUID viaje = UUID.fromString((String) sobre.get("id"));
        transfer(banco, viaje, "40000");
        assertThat(num(summary(), "saved", "crc")).isEqualByComparingTo("0");
        as(put("/api/spaces/" + space + "/places/" + viaje).content(toJson(Map.of("name", "Viaje",
                "openingBalance", "0", "openingDate", "2026-09-01", "archived", false, "savings", true))))
            .andExpect(status().isOk());
        assertThat(num(summary(), "saved", "crc")).isEqualByComparingTo("40000");

        // Editar sin mandar la marca la deja como estaba.
        Map<String, Object> editado = json(as(put("/api/spaces/" + space + "/places/" + viaje).content(toJson(Map.of(
                "name", "Viaje largo", "openingBalance", "0", "openingDate", "2026-09-01", "archived", false))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(editado).containsEntry("savings", true);
    }

    @Test
    void ligarUnaCuentaAUnaMetaLaVuelveDeAhorro() throws Exception {
        UUID space = ana.personalSpace();
        Map<String, Object> sobre = newPlace(space, "Carro", "SOBRE");
        as(post("/api/spaces/" + space + "/goals").content(toJson(Map.of("name", "Carro", "kind", "NORMAL",
                "targetAmount", "1000000", "currency", "CRC", "placeIds", List.of(sobre.get("id"))))))
            .andExpect(status().isCreated());
        try (Connection su = asSuperuser(); var ps = su.prepareStatement("SELECT savings FROM place WHERE id = ?")) {
            ps.setObject(1, UUID.fromString((String) sobre.get("id")));
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }

    // --- utilidades -----------------------------------------------------------------------------

    private Map<String, Object> newPlace(UUID space, String name, String kind) throws Exception {
        return json(as(post("/api/spaces/" + space + "/places").content(toJson(Map.of("name", name, "kind", kind,
                "currency", "CRC", "openingBalance", "0", "openingDate", "2026-09-01"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void insertTx(Connection su, UUID space, UUID category, String day, String amount, String currency,
                          UUID place) throws Exception {
        try (var ps = su.prepareStatement("""
                INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount, currency, place_id)
                VALUES (?, ?, ?, ?::date, 'Prueba', ?::numeric, ?, ?)""")) {
            ps.setObject(1, space);
            ps.setObject(2, ana.id());
            ps.setObject(3, category);
            ps.setString(4, day);
            ps.setString(5, amount);
            ps.setString(6, currency);
            ps.setObject(7, place);
            ps.executeUpdate();
        }
    }

    private static BigDecimal scalar(Connection su, String sql, UUID space) throws Exception {
        try (var ps = su.prepareStatement(sql)) {
            ps.setObject(1, space);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private void transfer(UUID from, UUID to, String amount) throws Exception {
        as(post("/api/spaces/" + ana.personalSpace() + "/transfers").content(toJson(Map.of(
            "from", from.toString(), "to", to.toString(), "date", DAY.toString(), "amount", amount))))
            .andExpect(status().isCreated());
    }

    private Map<String, Object> summary() throws Exception {
        return json(as(get("/api/spaces/" + ana.personalSpace() + "/summary?year=2026&month=9"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static BigDecimal num(Map<String, Object> s, String key, String field) {
        return new BigDecimal(String.valueOf(((Map<?, ?>) s.get(key)).get(field)));
    }

    private ResultActions as(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(ana))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static UUID place(Connection su, UUID space, String name, String kind) throws Exception {
        UUID id = UUID.randomUUID();
        try (var ps = su.prepareStatement("""
                INSERT INTO place (id, space_id, created_by, name, kind, currency, opening_balance, opening_date)
                SELECT ?, ?, m.user_id, ?, ?, 'CRC', 500000, DATE '2026-09-01' FROM space_member m WHERE m.space_id = ?""")) {
            ps.setObject(1, id);
            ps.setObject(2, space);
            ps.setString(3, name);
            ps.setString(4, kind);
            ps.setObject(5, space);
            ps.executeUpdate();
        }
        return id;
    }

    private void tx(Connection su, UUID space, UUID category, String amount) throws Exception {
        try (var ps = su.prepareStatement("""
                INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount, place_id)
                VALUES (?, ?, ?, DATE '2026-09-05', 'Prueba', ?::numeric, ?)""")) {
            ps.setObject(1, space);
            ps.setObject(2, ana.id());
            ps.setObject(3, category);
            ps.setString(4, amount);
            ps.setObject(5, banco);
            ps.executeUpdate();
        }
    }
}

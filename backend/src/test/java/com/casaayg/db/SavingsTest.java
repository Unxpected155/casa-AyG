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
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("100000"); // 300 000 / 3
                }
            }
        }
    }

    // --- utilidades -----------------------------------------------------------------------------

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

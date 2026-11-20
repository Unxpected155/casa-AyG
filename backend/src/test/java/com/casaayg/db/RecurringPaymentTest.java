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

import java.sql.Connection;
import java.sql.SQLException;
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

import com.casaayg.push.PushServiceTextAccess;

/** V9: pagos fijos, su pago ligado y el aviso diario. A y B no comparten espacio. */
class RecurringPaymentTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("America/Costa_Rica"));

    TestData.AppUser a;
    TestData.AppUser b;
    UUID servicios;
    UUID salario;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            servicios = TestData.categoryNamed(su, a.personalSpace(), "Servicios (luz, agua, internet)");
            salario = TestData.categoryNamed(su, a.personalSpace(), "Salario");
        }
    }

    @Test
    void pagarloLoMarcaComoPagadoEseMesYApagaElAviso() throws Exception {
        LocalDate tomorrow = TODAY.plusDays(1);
        UUID internet = create(a, "Internet", tomorrow.getDayOfMonth(), "18500", 1);
        assertThat(reminders(TODAY)).contains(a.id() + "|Internet|" + tomorrow);

        Map<String, Object> row = month(a, tomorrow).stream().filter(r -> internet.toString().equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("dueDate", tomorrow.toString()).containsEntry("paidTransactionId", null);

        UUID cuenta;
        try (Connection su = asSuperuser()) {
            cuenta = TestData.newPlace(su, a.personalSpace(), a.id());
        }
        Map<String, Object> pay = new HashMap<>(Map.of("categoryId", servicios.toString(), "date", tomorrow.toString(),
            "description", "Internet", "amount", "18900", "currency", "CRC", "recurringId", internet.toString(),
            "placeId", cuenta.toString()));
        as(a, post(base(a) + "/transactions").content(toJson(pay))).andExpect(status().isCreated());

        row = month(a, tomorrow).stream().filter(r -> internet.toString().equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(row.get("paidTransactionId")).isNotNull();
        assertThat(reminders(TODAY)).noneMatch(r -> r.startsWith(a.id() + "|Internet"));
    }

    @Test
    void sinDiaDePagoNoHayAviso() throws Exception {
        create(a, "Gas", null, null, 0);
        assertThat(reminders(TODAY)).noneMatch(r -> r.startsWith(a.id() + "|Gas"));
        assertThat(month(a, TODAY)).anyMatch(r -> "Gas".equals(r.get("name")) && r.get("dueDate") == null);
    }

    @Test
    void elDia31EnFebreroEsElUltimoDia() throws Exception {
        try (Connection su = asSuperuser(); var rs = su.createStatement().executeQuery(
                "SELECT recurring_due_date(31::smallint, DATE '2027-02-10'), recurring_due_date(31::smallint, DATE '2028-02-01')")) {
            rs.next();
            assertThat(rs.getObject(1, LocalDate.class)).isEqualTo(LocalDate.of(2027, 2, 28));
            assertThat(rs.getObject(2, LocalDate.class)).isEqualTo(LocalDate.of(2028, 2, 29));
        }
    }

    @Test
    void unIngresoNoEsUnPagoFijo() throws Exception {
        as(a, post(base(a) + "/recurring").content(toJson(body("Salario", salario, 1, null, 0))))
            .andExpect(status().isBadRequest());
        as(a, post(base(a) + "/recurring").content(toJson(body("Agua", servicios, 1, null, 2))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void bNoVeNiUsaLosPagosFijosDeA() throws Exception {
        UUID internet = create(a, "Internet", 15, "18500", 0);
        UUID categoryB;
        try (Connection su = asSuperuser()) {
            categoryB = TestData.categoryNamed(su, b.personalSpace(), "Supermercado");
        }
        as(b, get(base(a) + "/recurring?year=" + TODAY.getYear() + "&month=" + TODAY.getMonthValue()))
            .andExpect(status().isNotFound());
        as(b, put(base(a) + "/recurring/" + internet).content(toJson(body("X", servicios, 1, null, 0))))
            .andExpect(status().isNotFound());
        as(b, delete(base(a) + "/recurring/" + internet)).andExpect(status().isNotFound());
        // Pagar el pago fijo de A desde el espacio de B: 404.
        Map<String, Object> pay = new HashMap<>(Map.of("categoryId", categoryB.toString(), "date", TODAY.toString(),
            "description", "x", "amount", "1", "currency", "CRC", "recurringId", internet.toString()));
        as(b, post(base(b) + "/transactions").content(toJson(pay))).andExpect(status().isNotFound());

        // La BD también lo impide (trigger), aunque la app se equivocara.
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, b.id());
            assertThatThrownBy(() -> {
                try (var ps = app.prepareStatement("""
                        INSERT INTO casaayg.transaction (space_id, created_by, category_id, tx_date, description, amount, recurring_id)
                        VALUES (?, ?, ?, current_date, 'x', 1, ?)""")) {
                    ps.setObject(1, b.personalSpace());
                    ps.setObject(2, b.id());
                    ps.setObject(3, categoryB);
                    ps.setObject(4, internet);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void elTextoDelAvisoNoLlevaMontos() {
        LocalDate today = LocalDate.of(2026, 11, 14);
        String text = PushServiceTextAccess.recurringText(today, List.of(
            new String[] {"Internet", "2026-11-15"}, new String[] {"Agua", "2026-11-15"}, new String[] {"Luz", "2026-11-14"}));
        assertThat(text).isEqualTo("Hoy vence: Luz. Mañana vencen: Internet, Agua.");
    }

    // --- utilidades ----------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static String base(TestData.AppUser user) {
        return "/api/spaces/" + user.personalSpace();
    }

    private Map<String, Object> body(String name, UUID category, Integer day, String expected, int before) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "categoryId", category.toString(),
            "currency", "CRC", "notifyDaysBefore", before));
        if (day != null) {
            body.put("dueDay", day);
        }
        if (expected != null) {
            body.put("expectedAmount", expected);
        }
        return body;
    }

    private UUID create(TestData.AppUser user, String name, Integer day, String expected, int before) throws Exception {
        String res = as(user, post(base(user) + "/recurring").content(toJson(body(name, servicios, day, expected, before))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(res).get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> month(TestData.AppUser user, LocalDate day) throws Exception {
        String res = as(user, get(base(user) + "/recurring?year=" + day.getYear() + "&month=" + day.getMonthValue()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readValue(res, List.class);
    }

    private List<String> reminders(LocalDate day) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT user_id, name, due_date FROM casaayg.recurring_reminders(?)")) {
                ps.setObject(1, day);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getObject(3, LocalDate.class));
                    }
                }
            }
        }
        return out;
    }
}

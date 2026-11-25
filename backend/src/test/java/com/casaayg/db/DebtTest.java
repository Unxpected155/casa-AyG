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

/** V22: deudas, sus abonos y el aviso. A y B no comparten espacio. */
class DebtTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("America/Costa_Rica"));

    TestData.AppUser a;
    TestData.AppUser b;
    UUID deudas;
    UUID salario;
    UUID cuenta;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            deudas = TestData.categoryNamed(su, a.personalSpace(), "Pago de deudas");
            salario = TestData.categoryNamed(su, a.personalSpace(), "Salario");
            cuenta = TestData.newPlace(su, a.personalSpace(), a.id());
        }
    }

    @Test
    void cadaAbonoRebajaLoQueFaltaYAlFinalQuedaSaldada() throws Exception {
        UUID citas = create(a, "Mamá", "Citas médicas", "60000", null);
        UUID first = pay(a, citas, "20000").andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString().transform(s -> uuid(json(s).get("id")));

        Map<String, Object> row = debt(a, citas);
        assertThat(row).containsEntry("paid", 20000.0).containsEntry("remaining", 40000.0)
            .containsEntry("payments", 1).containsEntry("settledOn", null);

        pay(a, citas, "40000").andExpect(status().isCreated());
        assertThat(debt(a, citas)).containsEntry("remaining", 0.0).containsEntry("settledOn", TODAY.toString());

        // Borrar un abono devuelve la deuda sola.
        as(a, delete(base(a) + "/transactions/" + first)).andExpect(status().isNoContent());
        assertThat(debt(a, citas)).containsEntry("remaining", 20000.0).containsEntry("settledOn", null);
    }

    @Test
    void unAbonoNoPasaLoQueFaltaNiAlEditarlo() throws Exception {
        UUID sueter = create(a, "Mamá", "Suéter", "45000", null);
        pay(a, sueter, "45000.01").andExpect(status().isConflict());
        UUID tx = uuid(json(pay(a, sueter, "30000").andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString()).get("id"));
        pay(a, sueter, "15000.01").andExpect(status().isConflict());

        // Editar el abono: puede subir hasta lo que falta, contando su propio monto.
        Map<String, Object> edit = payment(sueter, "45000");
        edit.remove("debtId");
        as(a, put(base(a) + "/transactions/" + tx).content(toJson(edit))).andExpect(status().isOk());
        edit.put("amount", "45001");
        as(a, put(base(a) + "/transactions/" + tx).content(toJson(edit))).andExpect(status().isConflict());

        // La deuda no puede bajar de lo abonado.
        as(a, put(base(a) + "/debts/" + sueter).content(toJson(body("Mamá", "Suéter", "44000", null))))
            .andExpect(status().isConflict());
    }

    @Test
    void unAbonoEsUnGastoEnLaMonedaDeLaDeuda() throws Exception {
        UUID d = create(a, "Juan", "Almuerzo", "10000", null);
        Map<String, Object> income = payment(d, "1000");
        income.put("categoryId", salario.toString());
        as(a, post(base(a) + "/transactions").content(toJson(income))).andExpect(status().isBadRequest());

        Map<String, Object> usd = payment(d, "10");
        usd.put("currency", "USD");
        usd.remove("placeId");
        as(a, post(base(a) + "/transactions").content(toJson(usd))).andExpect(status().isConflict());
    }

    @Test
    void borrarLaDeudaDejaLosAbonosComoGastos() throws Exception {
        UUID d = create(a, "Mamá", "Citas", "5000", null);
        UUID tx = uuid(json(pay(a, d, "5000").andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString()).get("id"));
        as(a, delete(base(a) + "/debts/" + d)).andExpect(status().isNoContent());
        Map<String, Object> left = json(as(a, get(base(a) + "/transactions/" + tx)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(left).containsEntry("debtId", null).containsEntry("amount", 5000.0);
    }

    @Test
    void bNoVeNiAbonaLasDeudasDeA() throws Exception {
        UUID d = create(a, "Mamá", "Citas", "60000", null);
        UUID categoryB;
        UUID placeB;
        try (Connection su = asSuperuser()) {
            categoryB = TestData.categoryNamed(su, b.personalSpace(), "Pago de deudas");
            placeB = TestData.newPlace(su, b.personalSpace(), b.id());
        }
        as(b, get(base(a) + "/debts")).andExpect(status().isNotFound());
        as(b, put(base(a) + "/debts/" + d).content(toJson(body("X", "Y", "1", null)))).andExpect(status().isNotFound());
        as(b, delete(base(a) + "/debts/" + d)).andExpect(status().isNotFound());
        // Su propia lista no trae la deuda de A, y abonarla desde su espacio da 404.
        String listB = as(b, get(base(b) + "/debts")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        assertThat(listB).doesNotContain(d.toString());
        Map<String, Object> pay = payment(d, "1");
        pay.put("categoryId", categoryB.toString());
        pay.put("placeId", placeB.toString());
        as(b, post(base(b) + "/transactions").content(toJson(pay))).andExpect(status().isNotFound());
        // Ni la deuda ni el autor se eligen en el JSON (mass assignment).
        Map<String, Object> sneaky = new HashMap<>(body("Mamá", "x", "1", null));
        sneaky.put("spaceId", b.personalSpace().toString());
        as(a, post(base(a) + "/debts").content(toJson(sneaky))).andExpect(status().isBadRequest());

        // La BD también lo impide, aunque la app se equivocara.
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, b.id());
            assertThatThrownBy(() -> {
                try (var ps = app.prepareStatement("""
                        INSERT INTO casaayg.transaction (space_id, created_by, category_id, tx_date, description, amount, debt_id)
                        VALUES (?, ?, ?, current_date, 'x', 1, ?)""")) {
                    ps.setObject(1, b.personalSpace());
                    ps.setObject(2, b.id());
                    ps.setObject(3, categoryB);
                    ps.setObject(4, d);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            app.rollback();
            TestData.actAs(app, b.id());
            try (var rs = app.createStatement().executeQuery("SELECT count(*) FROM casaayg.debt")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void laBdNoDejaPasarseNiSinLaApp() throws Exception {
        UUID d = create(a, "Mamá", "Citas", "100", null);
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, a.id());
            assertThatThrownBy(() -> {
                try (var ps = app.prepareStatement("""
                        INSERT INTO casaayg.transaction (space_id, created_by, category_id, tx_date, description, amount, debt_id)
                        VALUES (?, ?, ?, current_date, 'x', 101, ?)""")) {
                    ps.setObject(1, a.personalSpace());
                    ps.setObject(2, a.id());
                    ps.setObject(3, deudas);
                    ps.setObject(4, d);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            app.rollback();
            // Ligar un gasto ya anotado tampoco puede pasarse, aunque se salte la app.
            TestData.actAs(app, a.id());
            UUID big = TestData.newTransaction(app, a.personalSpace(), a.id(), deudas, "101");
            assertThatThrownBy(() -> {
                try (var ps = app.prepareStatement("UPDATE casaayg.transaction SET debt_id = ? WHERE id = ?")) {
                    ps.setObject(1, d);
                    ps.setObject(2, big);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void elAvisoSoloDiceAQuienYSoloSiFaltaAlgo() throws Exception {
        create(a, "Mamá", "Citas médicas", "60000", TODAY.plusDays(1));
        UUID done = create(a, "Juan", "Almuerzo", "1000", TODAY);
        pay(a, done, "1000").andExpect(status().isCreated());
        assertThat(reminders(TODAY)).contains(a.id() + "|Mamá|" + TODAY.plusDays(1))
            .noneMatch(r -> r.contains("Juan"));

        String text = PushServiceTextAccess.debtText(LocalDate.of(2026, 11, 24), List.of(
            new String[] {"Juan", "2026-11-25", "FIN"}, new String[] {"Mamá", "2026-11-24", "FIN"},
            new String[] {"Ana", "2026-11-24", "CUOTA"}));
        assertThat(text).isEqualTo(
            "Hoy acordaste terminar de pagarle a Mamá. Hoy toca la cuota de Ana. Mañana acordaste terminar de pagarle a Juan.");
    }

    @Test
    void validaLoQueEntra() throws Exception {
        as(a, post(base(a) + "/debts").content(toJson(body("", "Citas", "1", null)))).andExpect(status().isBadRequest());
        as(a, post(base(a) + "/debts").content(toJson(body("Mamá", "Citas", "0", null)))).andExpect(status().isBadRequest());
        as(a, post(base(a) + "/debts").content(toJson(body("Mamá", "Ci\ntas", "1", null)))).andExpect(status().isBadRequest());
        as(a, post(base(a) + "/debts").content(toJson(body("Mamá", "Citas", "1", TODAY.minusDays(1)))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void loPagadoAntesRebajaLoQueFaltaPeroNoEsUnGasto() throws Exception {
        Map<String, Object> body = body("Mamá", "Citas médicas", "60000", null);
        body.put("paidBefore", "20000");
        UUID d = uuid(json(as(a, post(base(a) + "/debts").content(toJson(body))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("id"));
        assertThat(debt(a, d)).containsEntry("paidBefore", 20000.0).containsEntry("paid", 0.0)
            .containsEntry("remaining", 40000.0).containsEntry("payments", 0);
        String month = as(a, get(base(a) + "/transactions?year=" + TODAY.getYear() + "&month=" + TODAY.getMonthValue()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(month).get("total")).isEqualTo(0);
        pay(a, d, "40000.01").andExpect(status().isConflict());
        pay(a, d, "40000").andExpect(status().isCreated());
        assertThat(debt(a, d)).containsEntry("remaining", 0.0).containsEntry("settledOn", TODAY.toString());

        // Todo pagado antes no es una deuda; y la deuda no baja de lo cubierto.
        body.put("paidBefore", "60000");
        as(a, post(base(a) + "/debts").content(toJson(body))).andExpect(status().isBadRequest());
        body.put("paidBefore", "20000");
        body.put("amount", "59000");
        as(a, put(base(a) + "/debts/" + d).content(toJson(body))).andExpect(status().isConflict());
    }

    @Test
    void unGastoYaAnotadoSeLigaYSeSuelta() throws Exception {
        UUID d = create(a, "Mamá", "Suéter", "45000", null);
        Map<String, Object> plain = payment(d, "15000");
        plain.remove("debtId");
        UUID tx = uuid(json(as(a, post(base(a) + "/transactions").content(toJson(plain))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("id"));

        as(a, put(base(a) + "/transactions/" + tx + "/debt").content(toJson(Map.of("debtId", d.toString()))))
            .andExpect(status().isOk());
        assertThat(debt(a, d)).containsEntry("remaining", 30000.0).containsEntry("payments", 1);
        // Ligarlo otra vez a la misma deuda no lo cuenta dos veces.
        as(a, put(base(a) + "/transactions/" + tx + "/debt").content(toJson(Map.of("debtId", d.toString()))))
            .andExpect(status().isOk());
        assertThat(debt(a, d)).containsEntry("remaining", 30000.0);

        Map<String, Object> none = new HashMap<>();
        none.put("debtId", null);
        as(a, put(base(a) + "/transactions/" + tx + "/debt").content(toJson(none))).andExpect(status().isOk());
        assertThat(debt(a, d)).containsEntry("remaining", 45000.0).containsEntry("payments", 0);

        // Un gasto más grande que lo que falta no se liga.
        UUID small = create(a, "Juan", "Almuerzo", "1000", null);
        as(a, put(base(a) + "/transactions/" + tx + "/debt").content(toJson(Map.of("debtId", small.toString()))))
            .andExpect(status().isConflict());
        // Un ingreso no es abono.
        Map<String, Object> income = payment(d, "1000");
        income.remove("debtId");
        income.put("categoryId", salario.toString());
        UUID in = uuid(json(as(a, post(base(a) + "/transactions").content(toJson(income))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("id"));
        as(a, put(base(a) + "/transactions/" + in + "/debt").content(toJson(Map.of("debtId", d.toString()))))
            .andExpect(status().isConflict());

        // B no liga lo de A, ni su gasto a la deuda de A.
        as(b, put(base(a) + "/transactions/" + tx + "/debt").content(toJson(Map.of("debtId", d.toString()))))
            .andExpect(status().isNotFound());
        UUID txB;
        try (Connection su = asSuperuser()) {
            txB = TestData.newTransaction(su, b.personalSpace(), b.id(),
                TestData.categoryNamed(su, b.personalSpace(), "Pago de deudas"), "1");
        }
        as(b, put(base(b) + "/transactions/" + txB + "/debt").content(toJson(Map.of("debtId", d.toString()))))
            .andExpect(status().isNotFound());
    }

    @Test
    void perdonarSaldaSinGastoYSePuedeQuitar() throws Exception {
        UUID d = create(a, "Mamá", "Citas", "60000", null);
        pay(a, d, "20000").andExpect(status().isCreated());
        as(a, post(base(a) + "/debts/" + d + "/forgive")).andExpect(status().isOk());
        assertThat(debt(a, d)).containsEntry("forgiven", 40000.0).containsEntry("remaining", 0.0)
            .containsEntry("forgivenOn", TODAY.toString()).containsEntry("settledOn", TODAY.toString());
        pay(a, d, "1").andExpect(status().isConflict());
        as(a, post(base(a) + "/debts/" + d + "/forgive")).andExpect(status().isConflict());

        as(a, delete(base(a) + "/debts/" + d + "/forgive")).andExpect(status().isOk());
        assertThat(debt(a, d)).containsEntry("forgiven", 0.0).containsEntry("remaining", 40000.0)
            .containsEntry("settledOn", null);
        as(b, post(base(a) + "/debts/" + d + "/forgive")).andExpect(status().isNotFound());
    }

    @Test
    void laCuotaDelMesTieneAvisoHastaQueSeAbona() throws Exception {
        Map<String, Object> body = body("Ana", "Préstamo", "60000", null);
        body.put("installment", "10000");
        body.put("installmentDay", TODAY.getDayOfMonth());
        UUID d = uuid(json(as(a, post(base(a) + "/debts").content(toJson(body))).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("id"));
        assertThat(debt(a, d)).containsEntry("installment", 10000.0).containsEntry("paidThisMonth", 0.0)
            .containsEntry("installmentDueDate", TODAY.toString());
        assertThat(reminders(TODAY)).contains(a.id() + "|Ana|" + TODAY);

        pay(a, d, "10000").andExpect(status().isCreated());
        assertThat(debt(a, d)).containsEntry("paidThisMonth", 10000.0);
        assertThat(reminders(TODAY)).noneMatch(r -> r.contains("|Ana|"));

        // Día sin cuota, o cuota mayor a la deuda: no.
        body.put("installment", null);
        as(a, post(base(a) + "/debts").content(toJson(body))).andExpect(status().isBadRequest());
        body.put("installment", "60001");
        as(a, post(base(a) + "/debts").content(toJson(body))).andExpect(status().isBadRequest());
    }

    // --- utilidades ----------------------------------------------------------------------

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private static String base(TestData.AppUser user) {
        return "/api/spaces/" + user.personalSpace();
    }

    private Map<String, Object> body(String creditor, String concept, String amount, LocalDate due) {
        Map<String, Object> body = new HashMap<>(Map.of("creditor", creditor, "concept", concept, "amount", amount,
            "currency", "CRC", "borrowedOn", TODAY.toString()));
        if (due != null) {
            body.put("dueDate", due.toString());
        }
        return body;
    }

    private UUID create(TestData.AppUser user, String creditor, String concept, String amount, LocalDate due)
            throws Exception {
        String res = as(user, post(base(user) + "/debts").content(toJson(body(creditor, concept, amount, due))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(res).get("id"));
    }

    private Map<String, Object> payment(UUID debt, String amount) {
        return new HashMap<>(Map.of("categoryId", deudas.toString(), "date", TODAY.toString(),
            "description", "Abono", "amount", amount, "currency", "CRC", "placeId", cuenta.toString(),
            "debtId", debt.toString()));
    }

    private ResultActions pay(TestData.AppUser user, UUID debt, String amount) throws Exception {
        return as(user, post(base(user) + "/transactions").content(toJson(payment(debt, amount))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> debt(TestData.AppUser user, UUID id) throws Exception {
        String res = as(user, get(base(user) + "/debts")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        List<Map<String, Object>> list = JSON.readValue(res, List.class);
        return list.stream().filter(d -> id.toString().equals(d.get("id"))).findFirst().orElseThrow();
    }

    private List<String> reminders(LocalDate day) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT user_id, creditor, due_date FROM casaayg.debt_reminders(?)")) {
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

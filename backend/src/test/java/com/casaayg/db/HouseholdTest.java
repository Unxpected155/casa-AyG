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
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * V11, la casa al casarse. Un flujo realista: se casan, el salario entra a la casa, Ana
 * aporta de lo suyo, se cierra el mes con piso + bono, y cada quien solo ve el SALDO del otro.
 */
class HouseholdTest extends WebTestSupport {

    static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("America/Costa_Rica"));

    TestData.AppUser ana;
    TestData.AppUser beto;
    TestData.AppUser otro;
    UUID family;

    @BeforeEach
    void casarse() throws Exception {
        try (Connection su = asSuperuser()) {
            ana = TestData.adminCreateUser(su, "Ana");
            beto = TestData.adminCreateUser(su, "Beto");
            otro = TestData.adminCreateUser(su, "Otro");
        }
        String proposed = as(ana, post("/api/merge-requests").content(toJson(Map.of(
                "partnerUsername", beto.username(), "familyName", "Nuestro"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        family = uuid(json(as(beto, post("/api/merge-requests/" + json(proposed).get("id") + "/approve"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("familySpaceId"));
    }

    @Test
    void elMesCompletoDeLaCasa() throws Exception {
        UUID anaBanco = place(ana, ana.personalSpace(), "BCR", "200000");
        UUID betoSobre = place(beto, beto.personalSpace(), "Sobre libre", "0");
        UUID conjunta = place(ana, family, "Cuenta conjunta", "0");
        UUID fondo = place(ana, family, "Fondo de emergencia", "0");
        // Cada quien elige dónde recibe su dinero personal (Beto: su sobre; Ana no elige).
        as(beto, put("/api/me/personal-destination").content(toJson(Map.of("placeId", betoSobre.toString()))))
            .andExpect(status().isNoContent());
        // Beto no puede elegir un lugar que no es suyo.
        as(beto, put("/api/me/personal-destination").content(toJson(Map.of("placeId", anaBanco.toString()))))
            .andExpect(status().isNotFound());

        // Entran los salarios a la casa (con su diezmo) y hay gastos.
        tx(ana, family, "Salario", "1000000", conjunta, "Salario Ana");
        tx(ana, family, "Supermercado", "300000", conjunta, "Súper del mes");
        // Ana aporta 50 000 de lo suyo a la casa.
        as(ana, post("/api/spaces/" + family + "/household/contributions").content(toJson(Map.of(
                "fromPlace", anaBanco.toString(), "toPlace", conjunta.toString(), "amount", "50000",
                "date", TODAY.toString())))).andExpect(status().isCreated());

        YearMonth m = YearMonth.from(TODAY);
        // Sin monto fijo y sin bono no le toca nada a nadie: no se cierra en vacío.
        as(ana, post("/api/spaces/" + family + "/household/close").content(toJson(Map.of("year", m.getYear(),
                "month", m.getMonthValue(), "bonusPct", "0", "fromPlace", conjunta.toString()))))
            .andExpect(status().isConflict());
        // Antes de casarse no había casa: ese mes no se puede cerrar.
        as(ana, post("/api/spaces/" + family + "/household/close").content(closeBody(m.minusMonths(1), conjunta, null)))
            .andExpect(status().isConflict());

        // Ajustes: piso de 15 000 y bono de 2,5 %.
        as(ana, put("/api/spaces/" + family + "/household/settings").content(toJson(Map.of(
                "personalFloor", "15000", "bonusPct", "2.5")))).andExpect(status().isOk());

        Map<String, Object> preview = json(as(beto, get("/api/spaces/" + family + "/household/close?year="
                + m.getYear() + "&month=" + m.getMonthValue())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        // Entró 1 000 000 + 50 000 de aporte; salió 300 000 → sobran 750 000.
        assertThat(num(preview, "surplus")).isEqualByComparingTo("750000");
        assertThat(num(preview, "bonus")).isEqualByComparingTo("18750");
        assertThat(num(preview, "perPerson")).isEqualByComparingTo("33750");
        assertThat(num(preview, "rest")).isEqualByComparingTo("682500");

        // Cerrar pide passkey reciente.
        as(beto, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, fondo)),
            Duration.ofMinutes(10)).andExpect(status().isForbidden());
        as(beto, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, fondo)))
            .andExpect(status().isOk());
        // Una sola vez por mes.
        as(ana, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, fondo)))
            .andExpect(status().isConflict());

        // La casa: conjunta = 1 000 000 − 300 000 + 50 000 − 2 × 33 750 − 682 500 (al fondo) = 0.
        assertThat(balance(ana, family, conjunta)).isEqualByComparingTo("0");
        assertThat(balance(ana, family, fondo)).isEqualByComparingTo("682500");
        // Beto recibió en su sobre; Ana, sin destino, en su cuenta principal en colones (V12):
        // 200 000 − 50 000 de aporte + 33 750.
        assertThat(balance(beto, beto.personalSpace(), betoSobre)).isEqualByComparingTo("33750");
        assertThat(balance(ana, ana.personalSpace(), anaBanco)).isEqualByComparingTo("183750");

        // De la pareja solo se ve el SALDO personal, y sigue sin poder leer sus movimientos.
        Map<String, Object> house = json(as(ana, get("/api/spaces/" + family + "/household"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> partners = (List<Map<String, Object>>) house.get("partners");
        Map<String, Object> otraPersona = partners.stream().filter(p -> beto.id().toString().equals(p.get("userId"))).findFirst().orElseThrow();
        assertThat(num(otraPersona, "crc")).isEqualByComparingTo("33750");
        as(ana, get("/api/spaces/" + beto.personalSpace() + "/places")).andExpect(status().isNotFound());
        as(ana, get("/api/spaces/" + beto.personalSpace() + "/transactions?year=" + m.getYear()
            + "&month=" + m.getMonthValue())).andExpect(status().isNotFound());
    }

    @Test
    void alguienDeAfueraNoVeNiTocaLaCasa() throws Exception {
        UUID conjunta = place(ana, family, "Cuenta conjunta", "0");
        YearMonth m = YearMonth.from(TODAY);
        as(otro, get("/api/spaces/" + family + "/household")).andExpect(status().isNotFound());
        as(otro, get("/api/spaces/" + family + "/household/close?year=" + m.getYear() + "&month=" + m.getMonthValue()))
            .andExpect(status().isNotFound());
        as(otro, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, null)))
            .andExpect(status().isNotFound());
        // Un espacio personal no es una casa.
        as(ana, get("/api/spaces/" + ana.personalSpace() + "/household")).andExpect(status().isNotFound());
        // La función que paga al personal rechaza a quien no es de la casa.
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, otro.id());
            try (var ps = app.prepareStatement("SELECT casaayg.household_pay_personal(?, ?, 1000, current_date, 'x')")) {
                ps.setObject(1, family);
                ps.setObject(2, otro.id());
                org.assertj.core.api.Assertions.assertThatThrownBy(ps::executeQuery).isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    @Test
    void reabrirElMesDeshaceTodoYLoCerradoNoSeTocaAMano() throws Exception {
        UUID betoSobre = place(beto, beto.personalSpace(), "Sobre libre", "0");
        UUID conjunta = place(ana, family, "Cuenta conjunta", "0");
        UUID fondo = place(ana, family, "Fondo", "0");
        tx(ana, family, "Salario", "1000000", conjunta, "Salario");
        tx(ana, family, "Supermercado", "300000", conjunta, "Súper");
        tx(beto, beto.personalSpace(), "Supermercado", "1000", betoSobre, "Algo de Beto");
        as(ana, put("/api/spaces/" + family + "/household/settings").content(toJson(Map.of(
                "personalFloor", "15000", "bonusPct", "2.5")))).andExpect(status().isOk());
        YearMonth m = YearMonth.from(TODAY);
        as(ana, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, fondo)))
            .andExpect(status().isOk());
        assertThat(balance(beto, beto.personalSpace(), betoSobre)).isEqualByComparingTo("31500"); // −1000 + 15 000 + 2,5 % de 700 000

        // Lo que creó el cierre no se edita ni se borra a mano, ni en la casa ni en lo personal.
        UUID homeExpense = txNamed(ana, family, "Dinero personal de Beto", m);
        as(ana, delete("/api/spaces/" + family + "/transactions/" + homeExpense)).andExpect(status().isConflict());
        UUID personalIncome = txNamed(beto, beto.personalSpace(), "Dinero personal de", m);
        as(beto, delete("/api/spaces/" + beto.personalSpace() + "/transactions/" + personalIncome))
            .andExpect(status().isConflict());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transfers = JSON.readValue(as(ana, get("/api/spaces/" + family + "/transfers?year="
                + m.getYear() + "&month=" + m.getMonthValue())).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString(), List.class);
        assertThat(transfers).hasSize(1);
        as(ana, delete("/api/spaces/" + family + "/transfers/" + transfers.get(0).get("id")))
            .andExpect(status().isConflict());

        // Nadie puede ligar un movimiento personal de la pareja a un cierre (para borrarlo al
        // reabrir), ni pagarle dinero personal fuera de un cierre.
        UUID betoOther = txNamed(beto, beto.personalSpace(), "Algo de Beto", m);
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, ana.id());
            try (var ps = app.prepareStatement(
                    "INSERT INTO casaayg.month_close_item (space_id, year, month, tx_id) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, family);
                ps.setInt(2, m.getYear());
                ps.setInt(3, m.getMonthValue());
                ps.setObject(4, betoOther);
                org.assertj.core.api.Assertions.assertThatThrownBy(ps::executeUpdate).isInstanceOf(java.sql.SQLException.class);
            }
            app.rollback();
            TestData.actAs(app, ana.id());
            try (var ps = app.prepareStatement(
                    "SELECT casaayg.household_pay_personal(?, ?, 33750, current_date, 'x', ?::smallint, ?::smallint)")) {
                ps.setObject(1, family);
                ps.setObject(2, beto.id());
                ps.setInt(3, m.getYear());
                ps.setInt(4, m.getMonthValue());
                org.assertj.core.api.Assertions.assertThatThrownBy(ps::executeQuery).isInstanceOf(java.sql.SQLException.class);
            }
            app.rollback();
        }

        // Reabrir pide passkey reciente; después todo vuelve a como estaba y se puede cerrar otra vez.
        String reopen = "/api/spaces/" + family + "/household/close?year=" + m.getYear() + "&month=" + m.getMonthValue();
        as(beto, delete(reopen), Duration.ofMinutes(10)).andExpect(status().isForbidden());
        as(otro, delete(reopen)).andExpect(status().isNotFound());
        as(beto, delete(reopen)).andExpect(status().isNoContent());
        assertThat(balance(ana, family, conjunta)).isEqualByComparingTo("700000");
        assertThat(balance(ana, family, fondo)).isEqualByComparingTo("0");
        assertThat(balance(beto, beto.personalSpace(), betoSobre)).isEqualByComparingTo("-1000");
        as(beto, delete(reopen)).andExpect(status().isNotFound());
        as(ana, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, null)))
            .andExpect(status().isOk());
    }

    @Test
    void soloSeReabreElUltimoMesCerrado() throws Exception {
        try (Connection su = asSuperuser();
             var ps = su.prepareStatement("UPDATE casaayg.space SET created_at = now() - interval '45 days' WHERE id = ?")) {
            ps.setObject(1, family);
            ps.executeUpdate();
        }
        UUID conjunta = place(ana, family, "Cuenta conjunta", "0");
        as(ana, put("/api/spaces/" + family + "/household/settings").content(toJson(Map.of(
                "personalFloor", "10000", "bonusPct", "0")))).andExpect(status().isOk());
        YearMonth m = YearMonth.from(TODAY);
        as(ana, post("/api/spaces/" + family + "/household/close").content(closeBody(m.minusMonths(1), conjunta, null)))
            .andExpect(status().isOk());
        as(ana, post("/api/spaces/" + family + "/household/close").content(closeBody(m, conjunta, null)))
            .andExpect(status().isOk());
        as(ana, delete("/api/spaces/" + family + "/household/close?year=" + m.minusMonths(1).getYear()
            + "&month=" + m.minusMonths(1).getMonthValue())).andExpect(status().isConflict());
        as(ana, delete("/api/spaces/" + family + "/household/close?year=" + m.getYear()
            + "&month=" + m.getMonthValue())).andExpect(status().isNoContent());
    }

    @Test
    void laHistoriaDeLaCasaEmpiezaCuandoSeCasaron() throws Exception {
        YearMonth m = YearMonth.from(TODAY);
        Map<String, Object> history = json(as(ana, get("/api/spaces/" + family + "/history?year=" + m.getYear()
                + "&month=" + m.getMonthValue())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat((List<?>) history.get("months")).hasSize(1);
        // Lo personal conserva sus seis meses.
        Map<String, Object> personal = json(as(ana, get("/api/spaces/" + ana.personalSpace() + "/history?year="
                + m.getYear() + "&month=" + m.getMonthValue())).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString());
        assertThat((List<?>) personal.get("months")).hasSize(6);
    }

    // --- utilidades ----------------------------------------------------------------------

    private String closeBody(YearMonth m, UUID from, UUID rest) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("year", m.getYear(), "month", m.getMonthValue(),
            "bonusPct", "2.5", "fromPlace", from.toString()));
        if (rest != null) {
            body.put("restPlace", rest.toString());
        }
        return toJson(body);
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, Duration.ZERO);
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request, Duration ago) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user, ago))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private UUID place(TestData.AppUser user, UUID space, String name, String opening) throws Exception {
        String body = as(user, post("/api/spaces/" + space + "/places").content(toJson(Map.of("name", name,
                "kind", "BANCO", "currency", "CRC", "openingBalance", opening, "openingDate", TODAY.withDayOfMonth(1).toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    private void tx(TestData.AppUser user, UUID space, String category, String amount, UUID place, String description)
            throws Exception {
        UUID cat;
        try (Connection su = asSuperuser()) {
            cat = TestData.categoryNamed(su, space, category);
        }
        as(user, post("/api/spaces/" + space + "/transactions").content(toJson(Map.of("categoryId", cat.toString(),
                "date", TODAY.toString(), "description", description, "amount", amount, "currency", "CRC",
                "placeId", place.toString()))))
            .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    private UUID txNamed(TestData.AppUser user, UUID space, String prefix, YearMonth m) throws Exception {
        Map<String, Object> page = json(as(user, get("/api/spaces/" + space + "/transactions?year=" + m.getYear()
                + "&month=" + m.getMonthValue())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .filter(t -> String.valueOf(t.get("description")).startsWith(prefix))
            .map(t -> uuid(t.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private BigDecimal balance(TestData.AppUser user, UUID space, UUID place) throws Exception {
        List<Map<String, Object>> places = JSON.readValue(as(user, get("/api/spaces/" + space + "/places"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), List.class);
        return places.stream().filter(p -> place.toString().equals(p.get("id")))
            .map(p -> new BigDecimal(String.valueOf(p.get("balance")))).findFirst().orElseThrow();
    }

    private static BigDecimal num(Map<String, Object> map, String key) {
        return new BigDecimal(String.valueOf(map.get(key)));
    }
}

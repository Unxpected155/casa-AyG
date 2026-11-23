package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.casaayg.push.PushNotice;

/** V20: en la casa queda quién editó o borró qué, y se avisa a quien lo había anotado. */
@RecordApplicationEvents
class ChangeLogTest extends WebTestSupport {

    static final LocalDate DAY = LocalDate.now().withDayOfMonth(1);

    @Autowired ApplicationEvents events;

    TestData.AppUser ana;
    TestData.AppUser beto;
    TestData.AppUser carla;
    UUID casa;
    UUID supermercado;
    UUID conjunta;
    UUID sobre;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            ana = TestData.adminCreateUser(su, "Ana");
            beto = TestData.adminCreateUser(su, "Beto");
            carla = TestData.adminCreateUser(su, "Carla");
            casa = TestData.newSpace(su, "FAMILIA", ana.id(), beto.id());
            supermercado = TestData.newCategory(su, casa, ana.id(), "Supermercado", "NECESIDAD");
            conjunta = TestData.newPlace(su, casa, ana.id());
            sobre = TestData.newPlace(su, casa, ana.id());
        }
    }

    @Test
    void siBetoEditaOBorraLoDeAnaQuedaElAntesYDespuesYAnaSeEntera() throws Exception {
        UUID tx = uuid(json(as(ana, post(base() + "/transactions").content(tx("Super Walmart", "18450"))))
            .get("id"));
        events.clear();

        as(beto, put(base() + "/transactions/" + tx).content(tx("Super Walmart", "18540"))).andExpect(status().isOk());
        as(beto, get(base() + "/changes"))
            .andExpect(jsonPath("$[0].action").value("EDITO"))
            .andExpect(jsonPath("$[0].actorName").value("Beto"))
            .andExpect(jsonPath("$[0].before.monto").value(18450.00))
            .andExpect(jsonPath("$[0].after.monto").value(18540.00))
            .andExpect(jsonPath("$[0].before.categoria").value("Supermercado"));
        assertThat(events.stream(PushNotice.class))
            .singleElement().satisfies(n -> {
                assertThat(n.to()).containsExactly(ana.id());
                assertThat(n.body()).isEqualTo("Beto editó «Super Walmart», que anotaste.").doesNotContain("18");
            });

        events.clear();
        as(beto, delete(base() + "/transactions/" + tx)).andExpect(status().isNoContent());
        as(ana, get(base() + "/changes")).andExpect(jsonPath("$[0].action").value("BORRO"))
            .andExpect(jsonPath("$[0].after").doesNotExist())
            .andExpect(jsonPath("$.length()").value(2));
        assertThat(events.stream(PushNotice.class)).singleElement()
            .satisfies(n -> assertThat(n.body()).isEqualTo("Beto borró «Super Walmart», que anotaste."));
    }

    @Test
    void lasEdicionesPropiasQuedanPeroNoSeAvisanYLosPersonalesNoSeRegistran() throws Exception {
        UUID tx = uuid(json(as(ana, post(base() + "/transactions").content(tx("Pan", "1000")))).get("id"));
        events.clear();
        as(ana, put(base() + "/transactions/" + tx).content(tx("Pan y leche", "1500"))).andExpect(status().isOk());
        as(ana, get(base() + "/changes")).andExpect(jsonPath("$.length()").value(1));
        assertThat(events.stream(PushNotice.class)).isEmpty();

        // Guardar sin cambiar nada no es una edición.
        as(ana, put(base() + "/transactions/" + tx).content(tx("Pan y leche", "1500"))).andExpect(status().isOk());
        as(ana, get(base() + "/changes")).andExpect(jsonPath("$.length()").value(1));

        // En el espacio personal no se registra nada.
        UUID personal;
        UUID cat;
        UUID place;
        try (Connection su = asSuperuser()) {
            personal = ana.personalSpace();
            cat = TestData.categoryNamed(su, personal, "Supermercado");
            place = TestData.newPlace(su, personal, ana.id());
        }
        UUID mine = uuid(json(as(ana, post("/api/spaces/" + personal + "/transactions")
            .content(toJson(Map.of("categoryId", cat.toString(), "date", DAY.toString(), "description", "Algo",
                "amount", "10", "currency", "CRC", "placeId", place.toString()))))).get("id"));
        as(ana, delete("/api/spaces/" + personal + "/transactions/" + mine)).andExpect(status().isNoContent());
        as(ana, get("/api/spaces/" + personal + "/changes")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void borrarUnaTransferenciaTambienQueda() throws Exception {
        UUID x = uuid(json(as(ana, post(base() + "/transfers").content(toJson(Map.of("from", conjunta.toString(),
            "to", sobre.toString(), "date", DAY.toString(), "amount", "5000"))))).get("id"));
        as(beto, delete(base() + "/transfers/" + x)).andExpect(status().isNoContent());
        as(beto, get(base() + "/changes")).andExpect(jsonPath("$[0].entity").value("TRANSFERENCIA"))
            .andExpect(jsonPath("$[0].action").value("BORRO"))
            .andExpect(jsonPath("$[0].before.monto").value(5000.00));
    }

    @Test
    void nadieDeAfueraLoVeYLaAppNoLoPuedeTocar() throws Exception {
        UUID tx = uuid(json(as(ana, post(base() + "/transactions").content(tx("Pan", "1000")))).get("id"));
        as(beto, delete(base() + "/transactions/" + tx)).andExpect(status().isNoContent());
        as(carla, get(base() + "/changes")).andExpect(status().isNotFound());
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, ana.id());
            assertThatThrownBy(() -> app.createStatement().executeUpdate("DELETE FROM casaayg.change_log"))
                .hasMessageContaining("permission denied");
            app.rollback();
            TestData.actAs(app, ana.id());
            assertThatThrownBy(() -> app.createStatement().executeUpdate("UPDATE casaayg.change_log SET action = 'EDITO'"))
                .hasMessageContaining("permission denied");
            app.rollback();
        }
    }

    @Test
    void loQueHaceElSistemaNoSeRegistra() throws Exception {
        UUID tx = uuid(json(as(ana, post(base() + "/transactions").content(tx("Pan", "1000")))).get("id"));
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, ana.id());
            app.createStatement().execute("SELECT set_config('casaayg.system_change', 'on', true)");
            try (var ps = app.prepareStatement("DELETE FROM casaayg.transaction WHERE id = ?")) {
                ps.setObject(1, tx);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            app.commit();
        }
        as(ana, get(base() + "/changes")).andExpect(jsonPath("$.length()").value(0));
    }

    // --- utilidades -------------------------------------------------------------------------------

    private String base() {
        return "/api/spaces/" + casa;
    }

    private String tx(String description, String amount) {
        return toJson(Map.of("categoryId", supermercado.toString(), "date", DAY.toString(), "description", description,
            "amount", amount, "currency", "CRC", "placeId", conjunta.toString()));
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }

    private Map<String, Object> json(ResultActions r) throws Exception {
        return json(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
}

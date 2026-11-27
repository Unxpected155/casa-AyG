package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.casaayg.audit.AuditService;
import com.casaayg.push.PushKind;
import com.casaayg.push.PushNotice;
import com.casaayg.push.PushService;
import com.casaayg.push.PushServiceTextAccess;

/** Notificaciones por tipo (V27): interruptores, horas de silencio y uno al día. */
@RecordApplicationEvents
class PushRulesTest extends WebTestSupport {

    static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired PushService push;
    @Autowired AuditService audit;
    @Autowired ApplicationEvents events;

    TestData.AppUser a;
    TestData.AppUser b;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
        }
    }

    @Test
    void cadaTipoSeApagaPorPersonaMenosSeguridad() throws Exception {
        List<Map<String, Object>> prefs = preferences(a);
        assertThat(prefs).extracting(p -> p.get("kind")).containsExactly("SEGURIDAD", "POR_PAGAR", "CDP",
            "PRESUPUESTO", "CASA", "FACTURAS", "DIEZMO", "METAS");
        assertThat(prefs).allSatisfy(p -> assertThat(p).containsEntry("enabled", true));

        as(a, put("/api/push/preferences/FACTURAS").content("{\"enabled\":false}")).andExpect(status().isNoContent());
        assertThat(enabled(a, "FACTURAS")).isFalse();
        as(a, put("/api/push/preferences/FACTURAS").content("{\"enabled\":true}")).andExpect(status().isNoContent());
        assertThat(enabled(a, "FACTURAS")).isTrue();
        as(a, put("/api/push/preferences/DIEZMO").content("{\"enabled\":false}")).andExpect(status().isNoContent());
        // Lo de A no toca a B.
        assertThat(enabled(b, "DIEZMO")).isTrue();

        as(a, put("/api/push/preferences/SEGURIDAD").content("{\"enabled\":false}")).andExpect(status().isBadRequest());
        as(a, put("/api/push/preferences/CUALQUIERA").content("{\"enabled\":false}")).andExpect(status().isNotFound());
        assertThat(enabled(a, "SEGURIDAD")).isTrue();
    }

    @Test
    void apagadoNoSaleEnSilencioSeGuardaYSaleUnoAlDia() throws Exception {
        ZonedDateTime morning = LocalDate.now(CR).atTime(10, 0).atZone(CR);
        as(a, put("/api/push/preferences/METAS").content("{\"enabled\":false}")).andExpect(status().isNoContent());

        assertThat(decide(a, PushKind.METAS, morning)).isEqualTo("SKIP");
        assertThat(decide(a, PushKind.CDP, morning)).isEqualTo("SEND");
        assertThat(decide(a, PushKind.CDP, morning.plusHours(2))).isEqualTo("SKIP"); // ya salió uno hoy
        assertThat(decide(a, PushKind.CASA, morning)).isEqualTo("SEND");           // otro tipo, sí
        assertThat(decide(a, PushKind.CDP, morning.plusDays(1))).isEqualTo("SEND");  // otro día, sí

        // 9 p. m. a 7 a. m.: se guarda para las 7.
        assertThat(decide(a, PushKind.CASA, morning.withHour(21))).isEqualTo("HOLD");
        assertThat(decide(a, PushKind.CASA, morning.withHour(6).withMinute(59))).isEqualTo("HOLD");
        assertThat(decide(a, PushKind.POR_PAGAR, morning.withHour(7))).isEqualTo("SEND");
        assertThat(pending(a)).isEqualTo(2);
        assertThat(pending(b)).isZero();

        // Seguridad: a cualquier hora y sin tope.
        assertThat(decide(a, PushKind.SEGURIDAD, morning.withHour(23))).isEqualTo("SEND");
        assertThat(decide(a, PushKind.SEGURIDAD, morning.withHour(23))).isEqualTo("SEND");
    }

    @Test
    void unaPasskeyNuevaOUnCodigoDeRecuperacionAvisanDeSeguridad() {
        audit.record(a.id(), "PASSKEY_REGISTRADA", Map.of("etiqueta", "iPhone"), null);
        audit.record(a.id(), "LOGIN_RECUPERACION", null);
        audit.record(a.id(), "LOGIN_PASSKEY", null);
        assertThat(events.stream(PushNotice.class)).hasSize(2).allSatisfy(n -> {
            assertThat(n.kind()).isEqualTo(PushKind.SEGURIDAD);
            assertThat(n.to()).containsExactly(a.id());
            assertThat(n.body()).doesNotContain("iPhone");
        });
    }

    @Test
    void textosSinMontos() {
        assertThat(PushServiceTextAccess.cdpText(1, 0)).isEqualTo("Hoy vence tu CDP: toca renovarlo.");
        assertThat(PushServiceTextAccess.cdpText(0, 2)).isEqualTo("Mañana vencen 2 CDP.");
        assertThat(PushServiceTextAccess.invoiceText(1)).isEqualTo("Llegó una factura nueva para revisar.");
        assertThat(PushServiceTextAccess.invoiceText(3)).isEqualTo("Llegaron 3 facturas nuevas para revisar.");
        assertThat(PushServiceTextAccess.titheText(Set.of(true))).isEqualTo("En la casa hay diezmo por dar.");
        assertThat(PushServiceTextAccess.titheText(Set.of(true, false)))
            .isEqualTo("Tienes diezmo por dar en la casa y en lo personal.");
    }

    @Test
    void elCdpAvisaElDiaAntesYElDiaQueVenceNoDespues() throws Exception {
        UUID bank = place(a, "BCR", "BANCO");
        UUID cdp = place(a, "CDP", "CDP");
        LocalDate today = LocalDate.now();
        configureCdp(cdp, bank, today.plusDays(1));
        assertThat(cdpReminder(today)).containsExactly(0L, 1L);        // mañana
        assertThat(cdpReminder(today.plusDays(1))).containsExactly(1L, 0L); // hoy
        assertThat(cdpReminder(today.plusDays(2))).isEmpty();           // ya no insiste
    }

    // --- utilidades ----------------------------------------------------------------------

    private String decide(TestData.AppUser user, PushKind kind, ZonedDateTime now) {
        return PushServiceTextAccess.decide(push, user.id(), kind, now);
    }

    private long pending(TestData.AppUser user) throws Exception {
        try (Connection su = asSuperuser(); var ps = su.prepareStatement(
                "SELECT count(*) FROM casaayg.push_pending WHERE user_id = ?")) {
            ps.setObject(1, user.id());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<Long> cdpReminder(LocalDate day) throws Exception {
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT today, tomorrow FROM casaayg.cdp_reminders(?) WHERE user_id = ?")) {
                ps.setObject(1, day);
                ps.setObject(2, a.id());
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? List.of(rs.getLong(1), rs.getLong(2)) : List.of();
                }
            }
        }
    }

    private void configureCdp(UUID cdp, UUID bank, LocalDate maturesOn) throws Exception {
        as(a, put("/api/spaces/" + a.personalSpace() + "/places/" + cdp + "/cdp").content(toJson(Map.of(
                "rate", "2.85", "termDays", 30, "dayBasis", 360, "taxPct", "15",
                "maturesOn", maturesOn.toString(), "payoutPlace", bank.toString()))))
            .andExpect(status().isOk());
    }

    private UUID place(TestData.AppUser user, String name, String kind) throws Exception {
        String body = as(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/spaces/" + user.personalSpace() + "/places")
                .content(toJson(Map.of("name", name, "kind", kind, "currency", "CRC", "openingBalance", "1000",
                    "openingDate", LocalDate.now().toString()))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return uuid(json(body).get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preferences(TestData.AppUser user) throws Exception {
        return JSON.readValue(as(user, get("/api/push/preferences")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), List.class);
    }

    private boolean enabled(TestData.AppUser user, String kind) throws Exception {
        return preferences(user).stream().filter(p -> kind.equals(p.get("kind")))
            .map(p -> (Boolean) p.get("enabled")).findFirst().orElseThrow();
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Saldos en tiempo real (V26): lo que se anota en un espacio avisa a sus pantallas abiertas. */
class SpaceEventsTest extends WebTestSupport {

    TestData.AppUser a;
    TestData.AppUser b;
    UUID supermercadoA;
    UUID supermercadoB;
    UUID cuentaA;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            supermercadoA = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            supermercadoB = TestData.categoryNamed(su, b.personalSpace(), "Supermercado");
            cuentaA = TestData.newPlace(su, a.personalSpace(), a.id());
        }
    }

    @Test
    void loQueSeAnotaLlegaComoAvisoALasPantallasDeEseEspacioYNoALasDeOtro() throws Exception {
        MvcResult streamA = open(a, a.personalSpace());
        MvcResult streamB = open(b, b.personalSpace());
        waitFor(streamA, "event:ready");

        anotar(a, supermercadoA, cuentaA);
        waitFor(streamA, "event:change");

        // Lo de A no le llega a B, que no es miembro de ese espacio.
        Thread.sleep(500);
        assertThat(streamB.getResponse().getContentAsString()).doesNotContain("event:change");

        // El aviso no lleva datos: solo «cambió».
        assertThat(streamA.getResponse().getContentAsString()).doesNotContain("Pan").doesNotContain("1500");
    }

    @Test
    void unEspacioAjenoNoSeEscuchaYSinSesionTampoco() throws Exception {
        as(b, events(a.personalSpace())).andExpect(status().isNotFound());
        mvc.perform(events(a.personalSpace())).andExpect(status().isUnauthorized());
    }

    @Test
    void cadaPersonaTieneUnMaximoDePantallasAbiertas() throws Exception {
        List<MvcResult> streams = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            streams.add(open(a, a.personalSpace()));
        }
        waitFor(streams.get(7), "event:ready");
        Thread.sleep(300);
        // Las más viejas se cerraron: quedan 6 abiertas.
        assertThat(context.getBean(com.casaayg.space.SpaceEvents.class).openStreams(a.personalSpace()))
            .isEqualTo(6);
    }

    // --- utilidades ----------------------------------------------------------------------

    private MvcResult open(TestData.AppUser user, UUID space) throws Exception {
        return as(user, events(space)).andExpect(request().asyncStarted()).andReturn();
    }

    private static MockHttpServletRequestBuilder events(UUID space) {
        return get("/api/spaces/" + space + "/events").accept(MediaType.TEXT_EVENT_STREAM);
    }

    private void anotar(TestData.AppUser user, UUID category, UUID place) throws Exception {
        as(user, post("/api/spaces/" + user.personalSpace() + "/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("categoryId", category.toString(), "date", LocalDate.now().toString(),
                    "description", "Pan", "amount", "1500", "currency", "CRC", "placeId", place.toString()))))
            .andExpect(status().isCreated());
    }

    private static void waitFor(MvcResult stream, String text) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            if (stream.getResponse().getContentAsString().replace(" ", "").contains(text)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no llegó «" + text + "»: " + stream.getResponse().getContentAsString());
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf()));
    }
}

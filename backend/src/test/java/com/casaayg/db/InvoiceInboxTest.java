package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.casaayg.invoice.InvoiceInbox;
import com.casaayg.invoice.VerifiedInvoice;

/** V16: las facturas esperan a su dueño, que las confirma en un espacio o las descarta. */
class InvoiceInboxTest extends WebTestSupport {

    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    InvoiceInbox inbox;

    TestData.AppUser ana;
    TestData.AppUser beto;
    UUID supermercado;
    UUID banco;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            ana = TestData.adminCreateUser(su, "Ana");
            beto = TestData.adminCreateUser(su, "Beto");
            supermercado = TestData.categoryNamed(su, ana.personalSpace(), "Supermercado");
            banco = TestData.newPlace(su, ana.personalSpace(), ana.id());
        }
    }

    @Test
    void confirmarLaVuelveUnMovimientoYSugiereLoMismoLaProximaVez() throws Exception {
        VerifiedInvoice first = invoice("3101999999", "11300.00");
        assertThat(inbox.add(ana.id(), first)).isTrue();
        assertThat(inbox.add(ana.id(), first)).isFalse(); // la misma factura no entra dos veces

        String id = as(ana, get("/api/invoices")).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].merchant").value("Super Prueba"))
            .andExpect(jsonPath("$[0].suggestion").doesNotExist())
            .andReturn().getResponse().getContentAsString().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        as(beto, get("/api/invoices")).andExpect(jsonPath("$.length()").value(0));

        as(ana, post("/api/invoices/" + id + "/confirm").content(confirm(ana.personalSpace(), supermercado, banco)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value(11300.00))
            .andExpect(jsonPath("$.source").value("FACTURA"))
            .andExpect(jsonPath("$.description").value("Super Prueba"))
            .andExpect(jsonPath("$.date").value(LocalDate.now().toString()));
        as(ana, get("/api/invoices")).andExpect(jsonPath("$.length()").value(0));
        // Ya confirmada: no se confirma otra vez.
        as(ana, post("/api/invoices/" + id + "/confirm").content(confirm(ana.personalSpace(), supermercado, banco)))
            .andExpect(status().isNotFound());

        inbox.add(ana.id(), invoice("3101999999", "500.00"));
        as(ana, get("/api/invoices"))
            .andExpect(jsonPath("$[0].suggestion.spaceId").value(ana.personalSpace().toString()))
            .andExpect(jsonPath("$[0].suggestion.categoryId").value(supermercado.toString()))
            .andExpect(jsonPath("$[0].suggestion.placeId").value(banco.toString()));
    }

    @Test
    void nadieMasPuedeVerConfirmarNiDescartarMisFacturas() throws Exception {
        inbox.add(ana.id(), invoice("3101999999", "1000.00"));
        String id = idOfFirst(ana);
        UUID betoCat;
        UUID betoPlace;
        try (Connection su = asSuperuser()) {
            betoCat = TestData.categoryNamed(su, beto.personalSpace(), "Supermercado");
            betoPlace = TestData.newPlace(su, beto.personalSpace(), beto.id());
        }
        as(beto, post("/api/invoices/" + id + "/confirm").content(confirm(beto.personalSpace(), betoCat, betoPlace)))
            .andExpect(status().isNotFound());
        as(beto, post("/api/invoices/" + id + "/discard")).andExpect(status().isNotFound());
        // Ana tampoco puede mandarla al espacio de Beto, ni usar una categoría de otro espacio.
        as(ana, post("/api/invoices/" + id + "/confirm").content(confirm(beto.personalSpace(), betoCat, betoPlace)))
            .andExpect(status().isNotFound());
        as(ana, post("/api/invoices/" + id + "/confirm").content(confirm(ana.personalSpace(), betoCat, banco)))
            .andExpect(status().isNotFound());
        // Nada de eso la tocó: sigue por revisar.
        as(ana, get("/api/invoices")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void elMontoYLaFechaNoSeCambianAlConfirmar() throws Exception {
        inbox.add(ana.id(), invoice("3101999999", "1000.00"));
        String id = idOfFirst(ana);
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", ana.personalSpace().toString(),
            "categoryId", supermercado.toString(), "placeId", banco.toString(), "amount", "1"));
        as(ana, post("/api/invoices/" + id + "/confirm").content(toJson(body))).andExpect(status().isBadRequest());
    }

    @Test
    void unaCompraNoSeAnotaComoIngreso() throws Exception {
        inbox.add(ana.id(), invoice("3101999999", "1000.00"));
        String id = idOfFirst(ana);
        UUID salario;
        try (Connection su = asSuperuser()) {
            salario = TestData.categoryNamed(su, ana.personalSpace(), "Salario");
        }
        as(ana, post("/api/invoices/" + id + "/confirm").content(confirm(ana.personalSpace(), salario, banco)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Una compra se anota en una categoría de gasto, no de ingreso."));
        as(ana, get("/api/invoices")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void descartadaNoVuelveAEntrar() throws Exception {
        VerifiedInvoice v = invoice("3101999999", "1000.00");
        inbox.add(ana.id(), v);
        as(ana, post("/api/invoices/" + idOfFirst(ana) + "/discard")).andExpect(status().isNoContent());
        as(ana, get("/api/invoices")).andExpect(jsonPath("$.length()").value(0));
        assertThat(inbox.add(ana.id(), v)).isFalse();
    }

    @Test
    void laAppNoPuedeMeterFacturasDirecto() throws Exception {
        try (Connection app = asApp()) {
            assertThatThrownBy(() -> app.createStatement().executeUpdate("""
                    INSERT INTO casaayg.invoice_inbox (user_id, clave, kind, issued_on, merchant, emisor_id, currency, total)
                    VALUES (gen_random_uuid(), '506' || repeat('1', 47), 'FACTURA', current_date, 'x', '1', 'CRC', 1)"""))
                .hasMessageContaining("permission denied");
        }
    }

    // --- utilidades -----------------------------------------------------------------------------

    private static VerifiedInvoice invoice(String emisor, String total) {
        String clave = "506" + String.format("%047d", SEQ.incrementAndGet());
        return new VerifiedInvoice(VerifiedInvoice.Kind.FACTURA, clave, LocalDate.now(), "Super Prueba", emisor,
            "199999999", "CRC", new BigDecimal(total));
    }

    private String idOfFirst(TestData.AppUser user) throws Exception {
        String body = as(user, get("/api/invoices")).andReturn().getResponse().getContentAsString();
        List<?> list = JSON.readValue(body, List.class);
        return String.valueOf(((Map<?, ?>) list.getFirst()).get("id"));
    }

    private static String confirm(UUID space, UUID category, UUID place) {
        return toJson(Map.of("spaceId", space.toString(), "categoryId", category.toString(), "placeId", place.toString()));
    }

    private ResultActions as(TestData.AppUser user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(authentication(passkeyLogin(user))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON));
    }
}

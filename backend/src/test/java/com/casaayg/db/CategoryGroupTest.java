package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** V17: una categoría con movimientos no pasa entre ingreso y gasto (reescribiría los saldos). */
class CategoryGroupTest extends WebTestSupport {

    TestData.AppUser ana;
    UUID ropa;
    UUID regalos;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            ana = TestData.adminCreateUser(su, "Ana");
            ropa = TestData.categoryNamed(su, ana.personalSpace(), "Ropa");
            regalos = TestData.categoryNamed(su, ana.personalSpace(), "Regalos");
            TestData.newTransaction(su, ana.personalSpace(), ana.id(), ropa, "15000");
        }
    }

    @Test
    void conMovimientosNoPasaDeGastoAIngreso() throws Exception {
        update(ropa, "Ropa", "INGRESO").andExpect(status().isConflict());
        // Entre grupos de gasto sí: el signo no cambia.
        update(ropa, "Ropa", "NECESIDAD").andExpect(status().isNoContent());
        // Sin movimientos, cualquier cambio.
        update(regalos, "Regalos", "INGRESO").andExpect(status().isNoContent());
    }

    @Test
    void laBaseDeDatosTambienLoImpide() throws Exception {
        try (Connection su = asSuperuser(); var ps = su.prepareStatement(
                "UPDATE category SET category_group = 'INGRESO' WHERE id = ?")) {
            ps.setObject(1, ropa);
            assertThatThrownBy(ps::executeUpdate).hasMessageContaining("no puede pasar entre ingreso y gasto");
        }
    }

    private ResultActions update(UUID category, String name, String group) throws Exception {
        return mvc.perform(put("/api/spaces/" + ana.personalSpace() + "/categories/" + category)
            .content(toJson(Map.of("name", name, "group", group, "active", true)))
            .with(authentication(passkeyLogin(ana))).with(csrf()).contentType(MediaType.APPLICATION_JSON));
    }
}

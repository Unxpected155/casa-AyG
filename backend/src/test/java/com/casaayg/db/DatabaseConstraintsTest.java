package com.casaayg.db;

import static com.casaayg.db.TestData.addMember;
import static com.casaayg.db.TestData.newCategory;
import static com.casaayg.db.TestData.newSpace;
import static com.casaayg.db.TestData.newTransaction;
import static com.casaayg.db.TestData.newUser;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * La BD rechaza datos inválidos aunque la app tenga un bug.
 * Se ejecuta como superusuario a propósito: las restricciones aplican a TODOS los roles,
 * incluso a los que se saltan RLS.
 */
class DatabaseConstraintsTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION = "23503";

    @Test
    void montoNegativoSeRechaza() throws SQLException {
        try (Connection c = asSuperuser()) {
            UUID user = newUser(c);
            UUID space = newSpace(c, "PERSONAL", user);
            UUID category = newCategory(c, space, user, "Comida");
            assertThatThrownBy(() -> newTransaction(c, space, user, category, "-1.00"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void grupoDeCategoriaInvalidoSeRechaza() throws SQLException {
        try (Connection c = asSuperuser()) {
            UUID user = newUser(c);
            UUID space = newSpace(c, "PERSONAL", user);
            assertThatThrownBy(() -> c.createStatement().execute(
                    "INSERT INTO category (space_id, created_by, name, category_group) "
                        + "VALUES ('" + space + "', '" + user + "', 'X', 'LUJO')"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void presupuestoDuplicadoParaElMismoMesSeRechaza() throws SQLException {
        try (Connection c = asSuperuser()) {
            UUID user = newUser(c);
            UUID space = newSpace(c, "PERSONAL", user);
            String insert = "INSERT INTO budget (space_id, created_by, year, month) "
                + "VALUES ('" + space + "', '" + user + "', 2026, 11)";
            c.createStatement().execute(insert);
            assertThatThrownBy(() -> c.createStatement().execute(insert))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(UNIQUE_VIOLATION);
        }
    }

    @Test
    void movimientoConCategoriaDeOtroEspacioSeRechaza() throws SQLException {
        // Threat model 5.1-T: referencia cruzada. La FK compuesta (category_id, space_id) lo impide.
        try (Connection c = asSuperuser()) {
            UUID userA = newUser(c);
            UUID userB = newUser(c);
            UUID spaceA = newSpace(c, "PERSONAL", userA);
            UUID spaceB = newSpace(c, "PERSONAL", userB);
            UUID categoryOfB = newCategory(c, spaceB, userB, "Ropa");
            assertThatThrownBy(() -> newTransaction(c, spaceA, userA, categoryOfB, "10.00"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(FK_VIOLATION);
        }
    }

    @Test
    void unEspacioPersonalNoAceptaUnSegundoMiembro() throws SQLException {
        try (Connection c = asSuperuser()) {
            UUID owner = newUser(c);
            UUID intruder = newUser(c);
            UUID personal = newSpace(c, "PERSONAL", owner);
            assertThatThrownBy(() -> addMember(c, personal, intruder))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void facturaDuplicadaEnElMismoEspacioSeRechaza() throws SQLException {
        // La clave de 50 dígitos de Hacienda va en dedup_hash: la misma factura no entra dos veces.
        try (Connection c = asSuperuser()) {
            UUID user = newUser(c);
            UUID space = newSpace(c, "PERSONAL", user);
            UUID category = newCategory(c, space, user, "Super");
            String clave = "5" + "0".repeat(49);
            String insert = "INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, "
                + "amount, source, dedup_hash) VALUES ('" + space + "', '" + user + "', '" + category
                + "', CURRENT_DATE, 'Factura', 1000, 'FACTURA', '" + clave + "')";
            c.createStatement().execute(insert);
            assertThatThrownBy(() -> c.createStatement().execute(insert))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(UNIQUE_VIOLATION);
        }
    }
}

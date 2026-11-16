package com.casaayg.db;

import static com.casaayg.db.TestData.actAs;
import static com.casaayg.db.TestData.archive;
import static com.casaayg.db.TestData.newBudgetWithLine;
import static com.casaayg.db.TestData.newCategory;
import static com.casaayg.db.TestData.newSpace;
import static com.casaayg.db.TestData.newTransaction;
import static com.casaayg.db.TestData.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RLS por membresía de espacio (V3, ADR-005, threat model 5.1-E).
 *
 * Escenario: A y B son pareja. Cada uno tiene su espacio personal (privado) y comparten
 * uno familiar. C es un tercero sin relación. A además tiene un espacio personal archivado.
 */
class RowLevelSecurityTest extends PostgresTestBase {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String[] BUSINESS_TABLES = {"transaction", "category", "budget", "budget_line"};

    UUID userA, userB, userC;
    UUID personalA, personalB, personalC, family, archivedA;
    UUID categoryFamily, categoryB, transactionB;

    @BeforeEach
    void seed() throws SQLException {
        // El superusuario se salta RLS: sirve para preparar datos de todos.
        try (Connection su = asSuperuser()) {
            userA = newUser(su);
            userB = newUser(su);
            userC = newUser(su);
            personalA = newSpace(su, "PERSONAL", userA);
            personalB = newSpace(su, "PERSONAL", userB);
            personalC = newSpace(su, "PERSONAL", userC);
            family = newSpace(su, "FAMILIA", userA, userB);
            archivedA = newSpace(su, "PERSONAL", userA);
            archive(su, archivedA);

            UUID categoryA = newCategory(su, personalA, userA, "Comida");
            categoryB = newCategory(su, personalB, userB, "Comida");
            UUID categoryC = newCategory(su, personalC, userC, "Comida");
            categoryFamily = newCategory(su, family, userB, "Casa");

            newTransaction(su, personalA, userA, categoryA, "10.00");
            transactionB = newTransaction(su, personalB, userB, categoryB, "20.00");
            newTransaction(su, personalC, userC, categoryC, "30.00");
            newTransaction(su, family, userB, categoryFamily, "40.00");

            newBudgetWithLine(su, personalA, userA, categoryA, 2026);
            newBudgetWithLine(su, personalB, userB, categoryB, 2026);
            newBudgetWithLine(su, family, userB, categoryFamily, 2026);
        }
    }

    @Test
    void unSelectSinFiltroSoloDevuelveMisEspacios() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            // A propósito SIN "WHERE space_id = ?": es el bug que RLS debe contener.
            for (String table : BUSINESS_TABLES) {
                assertThat(spacesVisibleIn(c, table))
                    .as("espacios visibles para A en %s", table)
                    .doesNotContain(personalB, personalC)
                    .contains(family);
            }
            assertThat(spacesVisibleIn(c, "transaction")).containsExactlyInAnyOrder(personalA, family);
            c.rollback();
        }
    }

    @Test
    void elEspacioPersonalDeLaParejaEsPrivado() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            assertThat(idsIn(c, "SELECT id FROM space"))
                .containsExactlyInAnyOrder(personalA, family, archivedA);
            // Aunque A conozca el id exacto, no ve, no edita y no borra lo de B.
            assertThat(idsIn(c, "SELECT id FROM transaction WHERE id = '" + transactionB + "'")).isEmpty();
            assertThat(c.createStatement().executeUpdate(
                "UPDATE transaction SET amount = 0 WHERE id = '" + transactionB + "'")).isZero();
            assertThat(c.createStatement().executeUpdate(
                "DELETE FROM transaction WHERE id = '" + transactionB + "'")).isZero();
            c.rollback();
        }
    }

    @Test
    void cadaQuienVeSoloASuPareja() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            assertThat(idsIn(c, "SELECT id FROM app_user")).containsExactlyInAnyOrder(userA, userB);
            actAs(c, userC);
            assertThat(idsIn(c, "SELECT id FROM app_user")).containsExactly(userC);
            c.rollback();
        }
    }

    @Test
    void sinUsuarioEnLaSesionNoSeVeNada() throws SQLException {
        // Si la app olvida el set_config, RLS debe fallar CERRADO (0 filas), no abierto.
        try (Connection c = asApp()) {
            for (String table : new String[] {"transaction", "category", "budget", "budget_line",
                                              "space", "space_member", "app_user", "audit_log"}) {
                try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("filas visibles en %s", table).isZero();
                }
            }
        }
    }

    @Test
    void unUsuarioQueNoEsUuidFallaCerrado() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            c.createStatement().execute("SELECT set_config('app.current_user_id', 'admin', true)");
            assertThatThrownBy(() -> c.createStatement().executeQuery("SELECT * FROM transaction"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo("22P02");
            c.rollback();
        }
    }

    @Test
    void noSePuedeEscribirEnUnEspacioAjeno() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            assertThatThrownBy(() -> c.createStatement().execute(
                    "INSERT INTO budget (space_id, created_by, year, month) "
                        + "VALUES ('" + personalB + "', '" + userA + "', 2030, 1)"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(INSUFFICIENT_PRIVILEGE);
            c.rollback();
        }
    }

    @Test
    void noSePuedeRegistrarANombreDeLaPareja() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            // En el espacio familiar A sí puede escribir, pero como A, nunca como B.
            assertThatThrownBy(() -> newTransaction(c, family, userB, categoryFamily, "5.00"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(INSUFFICIENT_PRIVILEGE);
            c.rollback();
        }
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            newTransaction(c, family, userA, categoryFamily, "5.00");
            c.rollback();
        }
    }

    @Test
    void unEspacioArchivadoEsDeSoloLectura() throws SQLException {
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            assertThatThrownBy(() -> newCategory(c, archivedA, userA, "Nueva"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(INSUFFICIENT_PRIVILEGE);
            c.rollback();
        }
    }

    @Test
    void laAuditoriaEsPorUsuario() throws SQLException {
        try (Connection su = asSuperuser()) {
            su.createStatement().execute(
                "INSERT INTO audit_log (user_id, event_type) VALUES ('" + userB + "', 'LOGIN_OK')");
        }
        try (Connection c = asApp()) {
            c.setAutoCommit(false);
            actAs(c, userA);
            assertThat(idsIn(c, "SELECT user_id FROM audit_log")).doesNotContain(userB);
            assertThatThrownBy(() -> c.createStatement().execute(
                    "INSERT INTO audit_log (user_id, event_type) VALUES ('" + userB + "', 'FALSO')"))
                .isInstanceOf(SQLException.class)
                .extracting("SQLState").isEqualTo(INSUFFICIENT_PRIVILEGE);
            c.rollback();
            // Su propio evento sí se registra (en una transacción nueva).
            actAs(c, userA);
            c.createStatement().execute(
                "INSERT INTO audit_log (user_id, event_type) VALUES ('" + userA + "', 'LOGIN_OK')");
            c.rollback();
        }
    }

    private static Set<UUID> spacesVisibleIn(Connection c, String table) throws SQLException {
        return idsIn(c, "SELECT space_id FROM " + table);
    }

    private static Set<UUID> idsIn(Connection c, String sql) throws SQLException {
        Set<UUID> ids = new HashSet<>();
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getObject(1, UUID.class));
            }
        }
        return ids;
    }
}

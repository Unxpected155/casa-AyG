package com.casaayg.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Datos sintéticos para los tests. Todo con consultas parametrizadas.
 * Crear usuarios, espacios y miembros solo lo puede hacer el superusuario (la app no tiene
 * permisos para eso), así que esos helpers se usan con {@code asSuperuser()}.
 */
final class TestData {

    private TestData() {}

    static UUID newUser(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = c.prepareStatement("INSERT INTO app_user (id, username, display_name) VALUES (?, ?, 'Test')")) {
            ps.setObject(1, id);
            ps.setString(2, "u" + id.toString().replace("-", "").substring(0, 20));
            ps.executeUpdate();
        }
        return id;
    }

    /** Usuario completo como lo crea el administrador (V4): espacio personal, categorías y código. */
    record AppUser(UUID id, String username, String enrollmentCode, UUID personalSpace) {}

    static AppUser adminCreateUser(Connection su, String displayName) throws SQLException {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String code;
        try (var ps = su.prepareStatement("SELECT admin_create_user(?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, displayName);
            try (var rs = ps.executeQuery()) {
                rs.next();
                code = rs.getString(1);
            }
        }
        try (var ps = su.prepareStatement("""
                SELECT u.id, m.space_id FROM app_user u JOIN space_member m ON m.user_id = u.id
                 WHERE u.username = ?""")) {
            ps.setString(1, username);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return new AppUser(rs.getObject(1, UUID.class), username, code, rs.getObject(2, UUID.class));
            }
        }
    }

    static UUID categoryNamed(Connection su, UUID space, String name) throws SQLException {
        try (var ps = su.prepareStatement("SELECT id FROM category WHERE space_id = ? AND name = ?")) {
            ps.setObject(1, space);
            ps.setString(2, name);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /** Crea un espacio con sus miembros. */
    static UUID newSpace(Connection c, String kind, UUID... members) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = c.prepareStatement("INSERT INTO space (id, kind, name) VALUES (?, ?, 'Espacio')")) {
            ps.setObject(1, id);
            ps.setString(2, kind);
            ps.executeUpdate();
        }
        for (UUID member : members) {
            addMember(c, id, member);
        }
        return id;
    }

    static void addMember(Connection c, UUID space, UUID user) throws SQLException {
        try (var ps = c.prepareStatement("INSERT INTO space_member (space_id, user_id) VALUES (?, ?)")) {
            ps.setObject(1, space);
            ps.setObject(2, user);
            ps.executeUpdate();
        }
    }

    static void archive(Connection c, UUID space) throws SQLException {
        try (var ps = c.prepareStatement("UPDATE space SET archived_at = now() WHERE id = ?")) {
            ps.setObject(1, space);
            ps.executeUpdate();
        }
    }

    static UUID newCategory(Connection c, UUID space, UUID by, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = c.prepareStatement(
                "INSERT INTO category (id, space_id, created_by, name, category_group) VALUES (?, ?, ?, ?, 'NECESIDAD')")) {
            ps.setObject(1, id);
            ps.setObject(2, space);
            ps.setObject(3, by);
            ps.setString(4, name);
            ps.executeUpdate();
        }
        return id;
    }

    /** Una cuenta en colones con saldo 0 desde hace un año (lo que anota una persona lleva lugar). */
    static UUID newPlace(Connection c, UUID space, UUID by) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = c.prepareStatement("""
                INSERT INTO place (id, space_id, created_by, name, kind, currency, opening_balance, opening_date)
                VALUES (?, ?, ?, ?, 'BANCO', 'CRC', 0, CURRENT_DATE - 365)""")) {
            ps.setObject(1, id);
            ps.setObject(2, space);
            ps.setObject(3, by);
            ps.setString(4, "Cuenta " + id.toString().substring(0, 8));
            ps.executeUpdate();
        }
        return id;
    }

    static UUID newTransaction(Connection c, UUID space, UUID by, UUID category, String amount) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var ps = c.prepareStatement(
                "INSERT INTO transaction (id, space_id, created_by, category_id, tx_date, description, amount) "
                    + "VALUES (?, ?, ?, ?, CURRENT_DATE, 'Prueba', ?::numeric)")) {
            ps.setObject(1, id);
            ps.setObject(2, space);
            ps.setObject(3, by);
            ps.setObject(4, category);
            ps.setString(5, amount);
            ps.executeUpdate();
        }
        return id;
    }

    static UUID newBudgetWithLine(Connection c, UUID space, UUID by, UUID category, int year) throws SQLException {
        UUID budget = UUID.randomUUID();
        try (var ps = c.prepareStatement(
                "INSERT INTO budget (id, space_id, created_by, year, month) VALUES (?, ?, ?, ?, 11)")) {
            ps.setObject(1, budget);
            ps.setObject(2, space);
            ps.setObject(3, by);
            ps.setInt(4, year);
            ps.executeUpdate();
        }
        try (var ps = c.prepareStatement(
                "INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, 100.00)")) {
            ps.setObject(1, space);
            ps.setObject(2, budget);
            ps.setObject(3, category);
            ps.executeUpdate();
        }
        return budget;
    }

    /** Actúa como un usuario durante la transacción actual (el contrato de V3). */
    static void actAs(Connection c, UUID user) throws SQLException {
        try (var ps = c.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            ps.setString(1, user.toString());
            ps.execute();
        }
    }
}

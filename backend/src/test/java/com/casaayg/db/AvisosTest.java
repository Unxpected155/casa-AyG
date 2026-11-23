package com.casaayg.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.casaayg.push.PushServiceTextAccess;

/** V14: avisos de presupuesto (80 % y 100 %), recordatorio de cerrar el mes y la otra persona. */
class AvisosTest extends PostgresTestBase {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Costa_Rica"));

    TestData.AppUser a;
    TestData.AppUser b;
    UUID supermercado;
    UUID ahorro;

    @BeforeEach
    void seed() throws Exception {
        try (Connection su = asSuperuser()) {
            a = TestData.adminCreateUser(su, "A");
            b = TestData.adminCreateUser(su, "B");
            supermercado = TestData.categoryNamed(su, a.personalSpace(), "Supermercado");
            ahorro = TestData.newCategory(su, a.personalSpace(), a.id(), "Ahorro", "AHORRO");
            UUID budget = UUID.randomUUID();
            exec(su, "INSERT INTO budget (id, space_id, created_by, year, month) VALUES (?, ?, ?, ?, ?)",
                budget, a.personalSpace(), a.id(), TODAY.getYear(), TODAY.getMonthValue());
            exec(su, "INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, 100000)",
                a.personalSpace(), budget, supermercado);
            exec(su, "INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount) VALUES (?, ?, ?, 10000)",
                a.personalSpace(), budget, ahorro);
        }
    }

    @Test
    void elPresupuestoAvisaAl80YAl100UnaSolaVezYNoPorElAhorro() throws Exception {
        spend(supermercado, "70000", "CRC");
        spend(ahorro, "50000", "CRC"); // 500 % del ahorro planeado: pasarse ahí no es malo
        assertThat(budgetAlerts()).isEmpty();

        spend(supermercado, "15000", "CRC"); // 85 %
        assertThat(budgetAlerts()).containsExactly(a.id() + "|Supermercado|80|false");
        assertThat(budgetAlerts()).isEmpty(); // ya se avisó el 80

        try (Connection su = asSuperuser()) {
            exec(su, "INSERT INTO exchange_rate (rate_date, buy, sell, source) VALUES (?, 495, 500, 'MANUAL') "
                + "ON CONFLICT (rate_date) DO UPDATE SET sell = 500, buy = 495", TODAY.withDayOfMonth(1));
        }
        spend(supermercado, "40", "USD"); // ≈ ₡20 000 → más del 100 %
        assertThat(budgetAlerts()).containsExactly(a.id() + "|Supermercado|100|false");
        assertThat(budgetAlerts()).isEmpty();
        // B no tiene nada que ver con el presupuesto de A.
        assertThat(budgetAlerts()).noneMatch(s -> s.startsWith(b.id().toString()));
    }

    @Test
    void laCasaRecuerdaCerrarElMesAnteriorHastaQueSeCierre() throws Exception {
        UUID family;
        YearMonth prev = YearMonth.from(TODAY).minusMonths(1);
        try (Connection su = asSuperuser()) {
            family = TestData.newSpace(su, "FAMILIA", a.id(), b.id());
            exec(su, "UPDATE space SET created_at = now() - interval '45 days' WHERE id = ?", family);
        }
        assertThat(closeReminders()).contains(a.id() + "|" + prev, b.id() + "|" + prev);

        try (Connection su = asSuperuser()) {
            exec(su, "INSERT INTO month_close (space_id, year, month, closed_by, surplus, bonus_pct, per_person) "
                + "VALUES (?, ?, ?, ?, 0, 0, 0)", family, prev.getYear(), prev.getMonthValue(), a.id());
        }
        assertThat(closeReminders()).noneMatch(s -> s.startsWith(a.id().toString()) || s.startsWith(b.id().toString()));
    }

    @Test
    void unaCasaNuevaNoPideCerrarUnMesEnQueNoExistia() throws Exception {
        try (Connection su = asSuperuser()) {
            TestData.newSpace(su, "FAMILIA", a.id(), b.id());
        }
        assertThat(closeReminders()).noneMatch(s -> s.startsWith(a.id().toString()));
    }

    @Test
    void soloLasPartesDeUnaPropuestaSabenQuienEsLaOtraPersona() throws Exception {
        TestData.AppUser c;
        UUID request = UUID.randomUUID();
        try (Connection su = asSuperuser()) {
            c = TestData.adminCreateUser(su, "C");
            exec(su, "INSERT INTO merge_request (id, requester_id, partner_id, family_name) VALUES (?, ?, ?, 'Casa')",
                request, a.id(), b.id());
        }
        assertThat(otherParty(a.id(), request)).isEqualTo(b.id().toString());
        assertThat(otherParty(b.id(), request)).isEqualTo(a.id().toString());
        assertThat(otherParty(c.id(), request)).isNull();
    }

    @Test
    void losTextosNoLlevanMontos() {
        assertThat(PushServiceTextAccess.budgetText(List.of(
                new Object[] {"Supermercado", 80, false}, new Object[] {"Ropa", 100, true})))
            .isEqualTo("Supermercado va por el 80 % de lo planeado. En la casa, Ropa ya llegó al 100 % de lo planeado.")
            .doesNotContain("₡");
        assertThat(PushServiceTextAccess.closeReminderText(YearMonth.of(2026, 10)))
            .isEqualTo("Toca cerrar octubre: así cada quien recibe su dinero personal.");
    }

    // --- utilidades ----------------------------------------------------------------------

    private void spend(UUID category, String amount, String currency) throws Exception {
        try (Connection su = asSuperuser()) {
            exec(su, "INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount, currency) "
                + "VALUES (?, ?, ?, ?, 'Gasto', ?::numeric, ?)", a.personalSpace(), a.id(), category, TODAY, amount, currency);
        }
    }

    /** Como el aviso programado: sin sesión, y confirma (marca lo avisado). Solo las filas de A y B. */
    private List<String> budgetAlerts() throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            try (var ps = app.prepareStatement("SELECT user_id, category_name, level, family FROM casaayg.budget_alerts(?)")) {
                ps.setObject(1, TODAY);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String user = rs.getString(1);
                        if (user.equals(a.id().toString()) || user.equals(b.id().toString())) {
                            out.add(user + "|" + rs.getString(2) + "|" + rs.getInt(3) + "|" + rs.getBoolean(4));
                        }
                    }
                }
            }
            app.commit();
        }
        return out;
    }

    private List<String> closeReminders() throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection app = asApp()) {
            try (var ps = app.prepareStatement("SELECT user_id, year, month FROM casaayg.month_close_reminders(?)")) {
                ps.setObject(1, TODAY);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1) + "|" + YearMonth.of(rs.getInt(2), rs.getInt(3)));
                    }
                }
            }
        }
        return out;
    }

    private String otherParty(UUID me, UUID request) throws Exception {
        try (Connection app = asApp()) {
            app.setAutoCommit(false);
            TestData.actAs(app, me);
            try (var ps = app.prepareStatement("SELECT casaayg.merge_other_party(?)")) {
                ps.setObject(1, request);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            } finally {
                app.rollback();
            }
        }
    }

    private static void exec(Connection c, String sql, Object... params) throws Exception {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}

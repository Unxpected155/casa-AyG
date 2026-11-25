package com.casaayg.push;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.casaayg.shared.db.AsUser;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;

/**
 * Suscripciones de cada persona y avisos. El texto NUNCA lleva montos: puede verse en la
 * pantalla bloqueada. Lo que hay que mirar se abre dentro de la app.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");

    private final JdbcClient jdbc;
    private final WebPushSender sender;
    private final AsUser asUser;

    PushService(JdbcClient jdbc, WebPushSender sender, AsUser asUser) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.asUser = asUser;
    }

    public record Subscription(String endpoint, String p256dh, String auth) {}

    record Target(UUID id, String endpoint, String p256dh, String auth) {}

    @Transactional
    public void subscribe(Subscription s) {
        requireEnabled();
        if (!WebPushSender.allowedEndpoint(s.endpoint())) {
            throw new IllegalArgumentException("servicio de push no permitido");
        }
        try {
            WebPushCrypto.publicKey(WebPushCrypto.decode(s.p256dh()));
            if (WebPushCrypto.decode(s.auth()).length != 16) {
                throw new IllegalArgumentException("auth inválido");
            }
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("llaves de suscripción inválidas");
        }
        UUID me = CurrentUser.require();
        // Si este dispositivo ya estaba suscrito por la misma persona, se reemplaza.
        jdbc.sql("DELETE FROM push_subscription WHERE endpoint = ?").param(s.endpoint()).update();
        int inserted = jdbc.sql("""
                INSERT INTO push_subscription (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)
                ON CONFLICT (endpoint) DO NOTHING""")
            .params(me, s.endpoint(), s.p256dh(), s.auth()).update();
        if (inserted == 0) {
            throw new ConflictException("Este dispositivo ya recibe las notificaciones de otra persona.");
        }
    }

    @Transactional
    public void unsubscribe(String endpoint) {
        jdbc.sql("DELETE FROM push_subscription WHERE endpoint = ?").param(endpoint).update();
    }

    @Transactional(readOnly = true)
    public int count() {
        return jdbc.sql("SELECT count(*) FROM push_subscription").query(Integer.class).single();
    }

    /** Prueba desde la app: llega a los dispositivos de quien la pide. */
    public int test() {
        requireEnabled();
        return notify(CurrentUser.require(), "Casa A&G", "Las notificaciones funcionan en este dispositivo.", "/cuenta");
    }

    /** Cada mañana: a quien tenga un CDP que vence hoy (o venció sin renovar), un aviso sin montos. */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
    public void remindDueCdps() {
        if (!sender.enabled()) {
            return;
        }
        record Due(UUID userId, long count) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, cdp_count FROM cdp_due_users(?)")
            .param(LocalDate.now(COSTA_RICA))
            .query((rs, n) -> new Due(rs.getObject("user_id", UUID.class), rs.getLong("cdp_count"))).list());
        for (Due d : due) {
            notify(d.userId(), "Casa A&G", d.count() == 1 ? "Tu CDP venció: toca renovarlo."
                : "Tienes " + d.count() + " CDP por renovar.", "/lugares");
        }
        if (!due.isEmpty()) {
            log.info("Avisos de CDP enviados a {} personas", due.size());
        }
    }

    /**
     * Cada mañana: los pagos fijos que vencen hoy, mañana o en 3 días (según el aviso de cada
     * uno) y que ese mes no se han pagado. Un solo aviso por persona, con los nombres.
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
    public void remindRecurring() {
        if (!sender.enabled()) {
            return;
        }
        LocalDate today = LocalDate.now(COSTA_RICA);
        record Due(UUID userId, String name, LocalDate dueDate) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, name, due_date FROM recurring_reminders(?)")
            .param(today)
            .query((rs, n) -> new Due(rs.getObject("user_id", UUID.class), rs.getString("name"),
                rs.getObject("due_date", LocalDate.class))).list());
        due.stream().collect(java.util.stream.Collectors.groupingBy(Due::userId, java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()))
            .forEach((user, items) -> notify(user, "Pagos por hacer", recurringText(today, items.stream()
                .map(d -> new String[] {d.name(), d.dueDate().toString()}).toList()), "/"));
    }

    /**
     * Cada mañana: las deudas con fecha acordada (FIN) o cuota del mes (CUOTA) hoy o mañana a las
     * que aún les falta algo. Solo dice a quién (nunca montos ni el concepto). Un aviso por persona.
     */
    @Scheduled(cron = "0 5 8 * * *", zone = "America/Costa_Rica")
    public void remindDebts() {
        if (!sender.enabled()) {
            return;
        }
        LocalDate today = LocalDate.now(COSTA_RICA);
        record Due(UUID userId, String creditor, LocalDate dueDate, String kind) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, creditor, due_date, kind FROM debt_reminders(?)")
            .param(today)
            .query((rs, n) -> new Due(rs.getObject("user_id", UUID.class), rs.getString("creditor"),
                rs.getObject("due_date", LocalDate.class), rs.getString("kind"))).list());
        due.stream().collect(java.util.stream.Collectors.groupingBy(Due::userId, java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()))
            .forEach((user, items) -> notify(user, "Deudas", debtText(today, items.stream()
                .map(d -> new String[] {d.creditor(), d.dueDate().toString(), d.kind()}).toList()), "/deudas"));
    }

    /**
     * "Hoy acordaste terminar de pagarle a Mamá. Mañana toca la cuota de Juan."
     * Cada elemento: {persona, fecha, FIN|CUOTA}.
     */
    static String debtText(LocalDate today, List<String[]> items) {
        record Key(LocalDate date, boolean installment) {}
        java.util.Map<Key, java.util.Set<String>> groups = new java.util.TreeMap<>(
            java.util.Comparator.comparing(Key::date).thenComparing(Key::installment));
        for (String[] item : items) {
            boolean installment = item.length > 2 && "CUOTA".equals(item[2]);
            groups.computeIfAbsent(new Key(LocalDate.parse(item[1]), installment), k -> new java.util.LinkedHashSet<>())
                .add(item[0]);
        }
        List<String> parts = new java.util.ArrayList<>();
        groups.forEach((key, names) -> {
            String when = key.date().equals(today) ? "Hoy" : "Mañana";
            String who = String.join(", ", names);
            parts.add(key.installment()
                ? when + " toca la cuota de " + who + "."
                : when + " acordaste terminar de pagarle a " + who + ".");
        });
        String text = String.join(" ", parts);
        return text.length() > 200 ? text.substring(0, 197) + "…" : text;
    }

    /**
     * Mañana y tarde: las categorías de gasto que cruzaron el 80 % o el 100 % de lo planeado
     * este mes. Un solo aviso por persona; cada nivel se avisa una vez.
     */
    @Scheduled(cron = "0 0 8,19 * * *", zone = "America/Costa_Rica")
    public void warnBudgets() {
        if (!sender.enabled()) {
            return;
        }
        record Hit(UUID userId, String name, int level, boolean family) {}
        List<Hit> hits = asUser.run(null, () -> jdbc.sql("SELECT user_id, category_name, level, family FROM budget_alerts(?)")
            .param(LocalDate.now(COSTA_RICA))
            .query((rs, n) -> new Hit(rs.getObject("user_id", UUID.class), rs.getString("category_name"),
                rs.getInt("level"), rs.getBoolean("family"))).list());
        hits.stream().collect(java.util.stream.Collectors.groupingBy(Hit::userId, java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList()))
            .forEach((user, items) -> notify(user, "Presupuesto", budgetText(items.stream()
                .map(h -> new BudgetHit(h.name(), h.level(), h.family())).toList()), "/"));
    }

    record BudgetHit(String category, int level, boolean family) {}

    /** "Supermercado va por el 80 % de lo planeado." / "En la casa, Ropa ya llegó al 100 %." */
    static String budgetText(List<BudgetHit> hits) {
        List<String> parts = new java.util.ArrayList<>();
        for (BudgetHit h : hits) {
            String where = h.family() ? "En la casa, " : "";
            parts.add(where + h.category()
                + (h.level() >= 100 ? " ya llegó al 100 % de lo planeado." : " va por el 80 % de lo planeado."));
        }
        String text = String.join(" ", parts);
        return text.length() > 200 ? text.substring(0, 197) + "…" : text;
    }

    /** Los días 1 y 5: si la casa no ha cerrado el mes anterior, se lo recuerda a los dos. */
    @Scheduled(cron = "0 0 8 1,5 * *", zone = "America/Costa_Rica")
    public void remindMonthClose() {
        if (!sender.enabled()) {
            return;
        }
        record Due(UUID userId, int year, int month) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, year, month FROM month_close_reminders(?)")
            .param(LocalDate.now(COSTA_RICA))
            .query((rs, n) -> new Due(rs.getObject("user_id", UUID.class), rs.getInt("year"), rs.getInt("month"))).list());
        for (Due d : due) {
            notify(d.userId(), "La casa", closeReminderText(java.time.YearMonth.of(d.year(), d.month())), "/casa");
        }
    }

    static String closeReminderText(java.time.YearMonth month) {
        return "Toca cerrar " + monthName(month) + ": así cada quien recibe su dinero personal.";
    }

    static String monthName(java.time.YearMonth month) {
        return month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es-CR"));
    }

    /**
     * Avisos que nacen de algo que hizo alguien (ver {@link PushNotice}). Corre después de
     * confirmar la transacción; un error al enviar no debe deshacer ni romper esa acción.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onNotice(PushNotice notice) {
        if (!sender.enabled()) {
            return;
        }
        for (UUID user : notice.to()) {
            try {
                notify(user, notice.title(), notice.body(), notice.url());
            } catch (RuntimeException e) {
                log.warn("No se pudo enviar un aviso: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** Cada mañana: las metas que cruzaron 25, 50, 75 o 100 %. Solo el nombre y el %. */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
    public void celebrateGoals() {
        if (!sender.enabled()) {
            return;
        }
        record Hit(UUID userId, String name, int milestone) {}
        List<Hit> hits = asUser.run(null, () -> jdbc.sql("SELECT user_id, goal_name, milestone FROM goal_milestones(?)")
            .param(LocalDate.now(COSTA_RICA))
            .query((rs, n) -> new Hit(rs.getObject("user_id", UUID.class), rs.getString("goal_name"), rs.getInt("milestone")))
            .list());
        for (Hit h : hits) {
            notify(h.userId(), "Metas", goalText(h.name(), h.milestone()), "/metas");
        }
    }

    static String goalText(String name, int milestone) {
        String text = milestone >= 100 ? "¡Lo lograron! " + name + " está completa."
            : name + " ya va por el " + milestone + " %.";
        return text.length() > 200 ? text.substring(0, 197) + "…" : text;
    }

    /** "Hoy vence: Internet." / "Mañana vence: Agua." / "El 15 vence: Luz." (varios: separados por punto). */
    static String recurringText(LocalDate today, List<String[]> items) {
        java.util.Map<LocalDate, List<String>> byDate = new java.util.TreeMap<>();
        for (String[] item : items) {
            byDate.computeIfAbsent(LocalDate.parse(item[1]), k -> new java.util.ArrayList<>()).add(item[0]);
        }
        List<String> parts = new java.util.ArrayList<>();
        byDate.forEach((date, names) -> {
            String when = date.equals(today) ? "Hoy" : date.equals(today.plusDays(1)) ? "Mañana" : "El " + date.getDayOfMonth();
            parts.add(when + (names.size() == 1 ? " vence: " : " vencen: ") + String.join(", ", names) + ".");
        });
        String text = String.join(" ", parts);
        return text.length() > 200 ? text.substring(0, 197) + "…" : text;
    }

    /** Envía a todos los dispositivos de una persona; borra los que el servicio dio por muertos. */
    int notify(UUID userId, String title, String body, String url) {
        String json = "{\"title\":" + quote(title) + ",\"body\":" + quote(body) + ",\"url\":" + quote(url) + "}";
        List<Target> targets = asUser.run(userId, () -> jdbc.sql("SELECT id, endpoint, p256dh, auth FROM push_subscription")
            .query((rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getString("endpoint"),
                rs.getString("p256dh"), rs.getString("auth"))).list());
        int ok = 0;
        for (Target t : targets) {
            WebPushSender.Result result = sender.send(t.endpoint(), t.p256dh(), t.auth(), json);
            if (result == WebPushSender.Result.OK) {
                ok++;
                asUser.run(userId, () -> jdbc.sql("UPDATE push_subscription SET last_ok_at = now() WHERE id = ?")
                    .param(t.id()).update());
            } else if (result == WebPushSender.Result.GONE) {
                asUser.run(userId, () -> jdbc.sql("DELETE FROM push_subscription WHERE id = ?").param(t.id()).update());
            }
        }
        return ok;
    }

    private void requireEnabled() {
        if (!sender.enabled()) {
            throw new NotFoundException();
        }
    }

    /** JSON de un texto propio (sin datos del usuario), escapando lo mínimo. */
    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

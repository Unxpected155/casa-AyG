package com.casaayg.push;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

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
 *
 * Cada aviso tiene un tipo ({@link PushKind}) y pasa por {@link #decide}: si la persona lo
 * apagó, no sale; de 9 p. m. a 7 a. m. se guarda y sale a las 7; y de cada tipo sale uno al día
 * como máximo. SEGURIDAD se salta las tres reglas.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");
    static final LocalTime QUIET_FROM = LocalTime.of(21, 0);
    static final LocalTime QUIET_UNTIL = LocalTime.of(7, 0);

    private final JdbcClient jdbc;
    private final WebPushSender sender;
    private final AsUser asUser;

    PushService(JdbcClient jdbc, WebPushSender sender, AsUser asUser) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.asUser = asUser;
    }

    public record Subscription(String endpoint, String p256dh, String auth) {}

    public record Preference(PushKind kind, boolean enabled) {}

    record Target(UUID id, String endpoint, String p256dh, String auth) {}

    enum Decision { SEND, HOLD, SKIP }

    // --- Suscripciones y preferencias ----------------------------------------------------

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

    /** Todos los tipos, en orden, con lo que eligió la persona (sin elegir: encendido). */
    @Transactional(readOnly = true)
    public List<Preference> preferences() {
        CurrentUser.require();
        Map<String, Boolean> chosen = new java.util.HashMap<>();
        jdbc.sql("SELECT kind, enabled FROM push_preference")
            .query((rs, n) -> chosen.put(rs.getString(1), rs.getBoolean(2))).list();
        return Arrays.stream(PushKind.values())
            .map(k -> new Preference(k, !k.optional() || chosen.getOrDefault(k.name(), true)))
            .toList();
    }

    @Transactional
    public void setPreference(PushKind kind, boolean enabled) {
        if (!kind.optional()) {
            throw new IllegalArgumentException("los avisos de seguridad no se apagan");
        }
        jdbc.sql("""
                INSERT INTO push_preference (user_id, kind, enabled) VALUES (?, ?, ?)
                ON CONFLICT (user_id, kind) DO UPDATE SET enabled = EXCLUDED.enabled""")
            .params(CurrentUser.require(), kind.name(), enabled).update();
    }

    /** Prueba desde la app: llega a los dispositivos de quien la pide, sin reglas de tipo. */
    public int test() {
        requireEnabled();
        return deliver(CurrentUser.require(), "Casa A&G", "Las notificaciones funcionan en este dispositivo.", "/cuenta");
    }

    // --- Avisos programados ----------------------------------------------------------------

    /** 7 a. m.: lo que se guardó en horas de silencio. De cada tipo, el más reciente. */
    @Scheduled(cron = "0 0 7 * * *", zone = "America/Costa_Rica")
    public void releaseHeld() {
        if (!sender.enabled()) {
            return;
        }
        List<UUID> users = asUser.run(null, () -> jdbc.sql("SELECT * FROM push_pending_users()").query(UUID.class).list());
        for (UUID user : users) {
            record Held(String kind, String title, String body, String url) {}
            List<Held> held = asUser.run(user, () -> {
                List<Held> rows = jdbc.sql("SELECT kind, title, body, url FROM push_pending ORDER BY created_at")
                    .query((rs, n) -> new Held(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
                jdbc.sql("DELETE FROM push_pending").update();
                return rows;
            });
            Map<String, Held> latest = new LinkedHashMap<>();
            held.forEach(h -> latest.put(h.kind(), h));
            latest.values().forEach(h -> notify(user, PushKind.valueOf(h.kind()), h.title(), h.body(), h.url()));
        }
    }

    /** 8 a. m.: CDP que vencen mañana o hoy. No insiste después. */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
    public void remindCdps() {
        if (!sender.enabled()) {
            return;
        }
        record Due(UUID userId, long today, long tomorrow) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, today, tomorrow FROM cdp_reminders(?)")
            .param(today())
            .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3))).list());
        for (Due d : due) {
            notify(d.userId(), PushKind.CDP, "CDP", cdpText(d.today(), d.tomorrow()), "/lugares");
        }
    }

    static String cdpText(long today, long tomorrow) {
        List<String> parts = new ArrayList<>();
        if (today > 0) {
            parts.add(today == 1 ? "Hoy vence tu CDP: toca renovarlo." : "Hoy vencen " + today + " CDP: toca renovarlos.");
        }
        if (tomorrow > 0) {
            parts.add(tomorrow == 1 ? "Mañana vence tu CDP." : "Mañana vencen " + tomorrow + " CDP.");
        }
        return String.join(" ", parts);
    }

    /**
     * 8 a. m.: pagos fijos (según el aviso de cada uno) y deudas con fecha o cuota hoy o mañana a
     * las que les falta algo. Un solo aviso «Por pagar» por persona: nombres, nunca montos.
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
    public void remindPayments() {
        if (!sender.enabled()) {
            return;
        }
        LocalDate today = today();
        record Recurring(UUID userId, String name, LocalDate dueDate) {}
        record Debt(UUID userId, String creditor, LocalDate dueDate, String kind) {}
        List<Recurring> recurring = asUser.run(null, () -> jdbc.sql("SELECT user_id, name, due_date FROM recurring_reminders(?)")
            .param(today)
            .query((rs, n) -> new Recurring(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class)))
            .list());
        List<Debt> debts = asUser.run(null, () -> jdbc.sql("SELECT user_id, creditor, due_date, kind FROM debt_reminders(?)")
            .param(today)
            .query((rs, n) -> new Debt(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
                rs.getString(4)))
            .list());
        Set<UUID> users = new LinkedHashSet<>();
        recurring.forEach(r -> users.add(r.userId()));
        debts.forEach(d -> users.add(d.userId()));
        for (UUID user : users) {
            List<String[]> mineRecurring = recurring.stream().filter(r -> r.userId().equals(user))
                .map(r -> new String[] {r.name(), r.dueDate().toString()}).toList();
            List<String[]> mineDebts = debts.stream().filter(d -> d.userId().equals(user))
                .map(d -> new String[] {d.creditor(), d.dueDate().toString(), d.kind()}).toList();
            List<String> parts = new ArrayList<>();
            if (!mineRecurring.isEmpty()) {
                parts.add(recurringText(today, mineRecurring));
            }
            if (!mineDebts.isEmpty()) {
                parts.add(debtText(today, mineDebts));
            }
            notify(user, PushKind.POR_PAGAR, "Por pagar", limit(String.join(" ", parts)),
                mineRecurring.isEmpty() ? "/deudas" : "/");
        }
    }

    /**
     * 7 p. m.: las categorías de gasto que cruzaron el 80 % o el 100 % de lo planeado este mes.
     * Una vez al día (el tope por tipo); cada nivel se avisa una sola vez en el mes.
     */
    @Scheduled(cron = "0 0 19 * * *", zone = "America/Costa_Rica")
    public void warnBudgets() {
        if (!sender.enabled()) {
            return;
        }
        record Hit(UUID userId, String name, int level, boolean family) {}
        List<Hit> hits = asUser.run(null, () -> jdbc.sql("SELECT user_id, category_name, level, family FROM budget_alerts(?)")
            .param(today())
            .query((rs, n) -> new Hit(rs.getObject("user_id", UUID.class), rs.getString("category_name"),
                rs.getInt("level"), rs.getBoolean("family"))).list());
        hits.stream().collect(Collectors.groupingBy(Hit::userId, LinkedHashMap::new, Collectors.toList()))
            .forEach((user, items) -> notify(user, PushKind.PRESUPUESTO, "Presupuesto", budgetText(items.stream()
                .map(h -> new BudgetHit(h.name(), h.level(), h.family())).toList()), "/"));
    }

    /** Día 1: si la casa no ha cerrado el mes anterior, se lo recuerda a los dos. */
    @Scheduled(cron = "0 0 8 1 * *", zone = "America/Costa_Rica")
    public void remindMonthClose() {
        if (!sender.enabled()) {
            return;
        }
        record Due(UUID userId, int year, int month) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, year, month FROM month_close_reminders(?)")
            .param(today())
            .query((rs, n) -> new Due(rs.getObject("user_id", UUID.class), rs.getInt("year"), rs.getInt("month"))).list());
        for (Due d : due) {
            notify(d.userId(), PushKind.CASA, "La casa", closeReminderText(YearMonth.of(d.year(), d.month())), "/casa");
        }
    }

    /** 6 p. m.: un resumen de las facturas que llegaron por revisar en las últimas 24 horas. */
    @Scheduled(cron = "0 0 18 * * *", zone = "America/Costa_Rica")
    public void invoiceDigest() {
        if (!sender.enabled()) {
            return;
        }
        record Digest(UUID userId, long invoices) {}
        List<Digest> digest = asUser.run(null, () -> jdbc.sql("SELECT user_id, invoices FROM invoice_digest(?)")
            .param(ZonedDateTime.now(COSTA_RICA).minusHours(24).toOffsetDateTime())
            .query((rs, n) -> new Digest(rs.getObject(1, UUID.class), rs.getLong(2))).list());
        for (Digest d : digest) {
            notify(d.userId(), PushKind.FACTURAS, "Facturas", invoiceText(d.invoices()), "/facturas");
        }
    }

    static String invoiceText(long invoices) {
        return invoices == 1 ? "Llegó una factura nueva para revisar."
            : "Llegaron " + invoices + " facturas nuevas para revisar.";
    }

    /** Días 1 y 16, 9 a. m. (después de cada quincena): si hay diezmo por dar. */
    @Scheduled(cron = "0 0 9 1,16 * *", zone = "America/Costa_Rica")
    public void remindTithe() {
        if (!sender.enabled()) {
            return;
        }
        record Due(UUID userId, boolean family) {}
        List<Due> due = asUser.run(null, () -> jdbc.sql("SELECT user_id, family FROM tithe_reminders()")
            .query((rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getBoolean(2))).list());
        due.stream().collect(Collectors.groupingBy(Due::userId, LinkedHashMap::new,
                Collectors.mapping(Due::family, Collectors.toSet())))
            .forEach((user, where) -> notify(user, PushKind.DIEZMO, "Diezmo", titheText(where), "/"));
    }

    /** where: en qué espacios hay diezmo por dar (true = la casa, false = el personal). */
    static String titheText(Set<Boolean> where) {
        if (where.size() > 1) {
            return "Tienes diezmo por dar en la casa y en lo personal.";
        }
        return where.contains(true) ? "En la casa hay diezmo por dar." : "Tienes diezmo por dar.";
    }

    /** 8 a. m.: las metas que cruzaron 25, 50, 75 o 100 %. Solo el nombre y el %. */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Costa_Rica")
    public void celebrateGoals() {
        if (!sender.enabled()) {
            return;
        }
        record Hit(UUID userId, String name, int milestone) {}
        List<Hit> hits = asUser.run(null, () -> jdbc.sql("SELECT user_id, goal_name, milestone FROM goal_milestones(?)")
            .param(today())
            .query((rs, n) -> new Hit(rs.getObject("user_id", UUID.class), rs.getString("goal_name"), rs.getInt("milestone")))
            .list());
        hits.stream().collect(Collectors.groupingBy(Hit::userId, LinkedHashMap::new, Collectors.toList()))
            .forEach((user, items) -> notify(user, PushKind.METAS, "Metas", limit(items.stream()
                .map(h -> goalText(h.name(), h.milestone())).collect(Collectors.joining(" "))), "/metas"));
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
                notify(user, notice.kind(), notice.title(), notice.body(), notice.url());
            } catch (RuntimeException e) {
                log.warn("No se pudo enviar un aviso: {}", e.getClass().getSimpleName());
            }
        }
    }

    // --- Textos (sin montos) ---------------------------------------------------------------

    /**
     * "Hoy acordaste terminar de pagarle a Mamá. Mañana toca la cuota de Juan."
     * Cada elemento: {persona, fecha, FIN|CUOTA}.
     */
    static String debtText(LocalDate today, List<String[]> items) {
        record Key(LocalDate date, boolean installment) {}
        Map<Key, Set<String>> groups = new TreeMap<>(Comparator.comparing(Key::date).thenComparing(Key::installment));
        for (String[] item : items) {
            boolean installment = item.length > 2 && "CUOTA".equals(item[2]);
            groups.computeIfAbsent(new Key(LocalDate.parse(item[1]), installment), k -> new LinkedHashSet<>())
                .add(item[0]);
        }
        List<String> parts = new ArrayList<>();
        groups.forEach((key, names) -> {
            String when = key.date().equals(today) ? "Hoy" : "Mañana";
            String who = String.join(", ", names);
            parts.add(key.installment()
                ? when + " toca la cuota de " + who + "."
                : when + " acordaste terminar de pagarle a " + who + ".");
        });
        return limit(String.join(" ", parts));
    }

    record BudgetHit(String category, int level, boolean family) {}

    /** "Supermercado va por el 80 % de lo planeado." / "En la casa, Ropa ya llegó al 100 %." */
    static String budgetText(List<BudgetHit> hits) {
        List<String> parts = new ArrayList<>();
        for (BudgetHit h : hits) {
            String where = h.family() ? "En la casa, " : "";
            parts.add(where + h.category()
                + (h.level() >= 100 ? " ya llegó al 100 % de lo planeado." : " va por el 80 % de lo planeado."));
        }
        return limit(String.join(" ", parts));
    }

    static String closeReminderText(YearMonth month) {
        return "Toca cerrar " + monthName(month) + ": así cada quien recibe su dinero personal.";
    }

    static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es-CR"));
    }

    static String goalText(String name, int milestone) {
        String text = milestone >= 100 ? "¡Lo lograron! " + name + " está completa."
            : name + " ya va por el " + milestone + " %.";
        return limit(text);
    }

    /** "Hoy vence: Internet." / "Mañana vence: Agua." / "El 15 vence: Luz." (varios: separados por punto). */
    static String recurringText(LocalDate today, List<String[]> items) {
        Map<LocalDate, List<String>> byDate = new TreeMap<>();
        for (String[] item : items) {
            byDate.computeIfAbsent(LocalDate.parse(item[1]), k -> new ArrayList<>()).add(item[0]);
        }
        List<String> parts = new ArrayList<>();
        byDate.forEach((date, names) -> {
            String when = date.equals(today) ? "Hoy" : date.equals(today.plusDays(1)) ? "Mañana" : "El " + date.getDayOfMonth();
            parts.add(when + (names.size() == 1 ? " vence: " : " vencen: ") + String.join(", ", names) + ".");
        });
        return limit(String.join(" ", parts));
    }

    private static String limit(String text) {
        return text.length() > 200 ? text.substring(0, 197) + "…" : text;
    }

    // --- Envío -------------------------------------------------------------------------------

    /** Un aviso de un tipo: pasa por las reglas de la persona y, si toca, sale. */
    int notify(UUID userId, PushKind kind, String title, String body, String url) {
        Decision decision = decide(userId, kind, ZonedDateTime.now(COSTA_RICA), title, body, url);
        return decision == Decision.SEND ? deliver(userId, title, body, url) : 0;
    }

    /**
     * Las reglas, en orden: apagado → no sale; horas de silencio → se guarda para las 7;
     * ya salió uno de ese tipo hoy → no sale. SEGURIDAD siempre sale.
     */
    Decision decide(UUID userId, PushKind kind, ZonedDateTime now, String title, String body, String url) {
        if (!kind.optional()) {
            return Decision.SEND;
        }
        return asUser.run(userId, () -> {
            boolean enabled = jdbc.sql("SELECT enabled FROM push_preference WHERE kind = ?").param(kind.name())
                .query(Boolean.class).optional().orElse(true);
            if (!enabled) {
                return Decision.SKIP;
            }
            if (quiet(now.toLocalTime())) {
                jdbc.sql("INSERT INTO push_pending (user_id, kind, title, body, url) VALUES (?, ?, ?, ?, ?)")
                    .params(userId, kind.name(), title, body, url).update();
                return Decision.HOLD;
            }
            int first = jdbc.sql("INSERT INTO push_sent (user_id, kind, day) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(userId, kind.name(), now.toLocalDate()).update();
            return first == 1 ? Decision.SEND : Decision.SKIP;
        });
    }

    static boolean quiet(LocalTime time) {
        return !time.isBefore(QUIET_FROM) || time.isBefore(QUIET_UNTIL);
    }

    /** Envía a todos los dispositivos de una persona; borra los que el servicio dio por muertos. */
    int deliver(UUID userId, String title, String body, String url) {
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

    private static LocalDate today() {
        return LocalDate.now(COSTA_RICA);
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

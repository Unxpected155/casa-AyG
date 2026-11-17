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

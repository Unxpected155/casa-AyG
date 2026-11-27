package com.casaayg.space;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Saldos en tiempo real. La BD avisa por NOTIFY 'space_changed' (V26) cada vez que cambia algo
 * de un espacio; aquí se escucha ese canal y se reenvía «cambió» a las pantallas abiertas de
 * ese espacio, por Server-Sent Events. El aviso no lleva datos: la pantalla los vuelve a pedir
 * por la API, con la membresía y RLS de siempre.
 *
 * Cada conexión dura {@link #STREAM_TIMEOUT}: al reconectarse, el navegador vuelve a pasar por
 * la sesión y la membresía, así que una sesión cerrada o un espacio que ya no es tuyo deja de
 * recibir avisos en ese tiempo como máximo.
 */
@Component
public class SpaceEvents implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SpaceEvents.class);

    static final String CHANNEL = "space_changed";
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);
    /** Un comentario cada tanto mantiene viva la conexión a través de proxies. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(25);
    /** Pantallas abiertas a la vez por persona (teléfono, computadora, pestañas…). */
    static final int MAX_STREAMS_PER_USER = 6;

    private record Stream(SseEmitter emitter, UUID spaceId, UUID userId) {}

    private final DataSource dataSource;
    private final Map<UUID, Set<Stream>> bySpace = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Stream>> byUser = new ConcurrentHashMap<>();
    /** Escribir a un navegador lento no detiene la escucha de la BD. */
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    /** Cada arranque tiene su número: una escucha vieja que sigue viva se retira sola. */
    private volatile int generation;
    private Thread listener;

    SpaceEvents(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Abre un flujo de avisos. Quien llama ya comprobó que la persona es miembro del espacio. */
    public SseEmitter subscribe(UUID spaceId, UUID userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        Stream stream = new Stream(emitter, spaceId, userId);
        Runnable remove = () -> remove(stream);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());

        Stream evicted = null;
        Deque<Stream> mine = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (mine) {
            mine.addLast(stream);
            if (mine.size() > MAX_STREAMS_PER_USER) {
                evicted = mine.pollFirst();
            }
        }
        bySpace.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet()).add(stream);
        if (evicted != null) {
            evicted.emitter().complete();
            remove(evicted);
        }
        // Primer mensaje: confirma la conexión y le dice al navegador cada cuánto reintentar.
        send(stream, SseEmitter.event().name("ready").data("ok").reconnectTime(3000));
        return emitter;
    }

    /** Avisa a todas las pantallas abiertas de ese espacio. */
    void publish(UUID spaceId) {
        Set<Stream> streams = bySpace.get(spaceId);
        if (streams == null) {
            return;
        }
        for (Stream s : streams) {
            send(s, SseEmitter.event().name("change").data("1"));
        }
    }

    /** Pantallas abiertas de un espacio (para los tests). */
    public int openStreams(UUID spaceId) {
        Set<Stream> streams = bySpace.get(spaceId);
        return streams == null ? 0 : streams.size();
    }

    private void heartbeat() {
        for (Set<Stream> streams : bySpace.values()) {
            for (Stream s : streams) {
                send(s, SseEmitter.event().comment("·"));
            }
        }
    }

    private void send(Stream stream, SseEmitter.SseEventBuilder event) {
        senders.execute(() -> {
            try {
                stream.emitter().send(event);
            } catch (IOException | IllegalStateException e) {
                // El navegador se fue: se cierra y se olvida.
                stream.emitter().completeWithError(e);
                remove(stream);
            }
        });
    }

    private void remove(Stream stream) {
        Set<Stream> streams = bySpace.get(stream.spaceId());
        if (streams != null) {
            streams.remove(stream);
            if (streams.isEmpty()) {
                bySpace.remove(stream.spaceId(), streams);
            }
        }
        Deque<Stream> mine = byUser.get(stream.userId());
        if (mine != null) {
            synchronized (mine) {
                mine.remove(stream);
            }
        }
    }

    // --- Escucha de la BD -----------------------------------------------------------------

    private void listen(int mine) {
        while (running && mine == generation) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                long lastBeat = System.nanoTime();
                while (running && mine == generation) {
                    PGNotification[] notifications = pg.getNotifications((int) HEARTBEAT.toMillis());
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            publishSafely(n.getParameter());
                        }
                    }
                    if (System.nanoTime() - lastBeat >= HEARTBEAT.toNanos()) {
                        heartbeat();
                        lastBeat = System.nanoTime();
                    }
                }
            } catch (Exception e) {
                if (!running || mine != generation) {
                    return;
                }
                log.warn("Se perdió la escucha de cambios en la BD; se reintenta en 5 s: {}", e.toString());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void publishSafely(String payload) {
        try {
            publish(UUID.fromString(payload));
        } catch (IllegalArgumentException e) {
            log.warn("Aviso de cambio con un id inválido");
        }
    }

    @Override
    public void start() {
        running = true;
        int mine = ++generation;
        listener = Thread.ofPlatform().daemon().name("space-events").start(() -> listen(mine));
    }

    @Override
    public void stop() {
        running = false;
        if (listener != null) {
            listener.interrupt();
        }
        bySpace.values().forEach(streams -> streams.forEach(s -> s.emitter().complete()));
        bySpace.clear();
        byUser.clear();
        // El ejecutor de hilos virtuales no se apaga: Spring puede detener y volver a arrancar
        // este componente (por ejemplo, al pausar un contexto en los tests).
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

package com.casaayg.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Freno a la fuerza bruta de códigos: tras 5 fallos para un usuario o 20 desde una IP en
 * 15 minutos, se rechaza sin siquiera consultar la BD. En memoria: hay una sola instancia.
 * (Los códigos tienen 96 bits, así que adivinar es inviable; esto corta el ruido y el abuso.)
 */
@Component
public class LoginThrottle {

    static final int MAX_PER_USER = 5;
    static final int MAX_PER_IP = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String username, String ip) {
        return count("u:" + username) >= MAX_PER_USER || count("ip:" + ip) >= MAX_PER_IP;
    }

    public void recordFailure(String username, String ip) {
        if (failures.size() > MAX_KEYS) {
            failures.clear(); // tope de memoria: nadie puede llenar el mapa con usuarios inventados
        }
        add("u:" + username);
        add("ip:" + ip);
    }

    public void reset(String username) {
        failures.remove("u:" + username);
    }

    private void add(String key) {
        Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(clock.instant());
        }
    }

    private int count(String key) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(WINDOW);
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
                times.pollFirst();
            }
            return times.size();
        }
    }
}

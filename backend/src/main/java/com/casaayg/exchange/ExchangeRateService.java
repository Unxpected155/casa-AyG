package com.casaayg.exchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Tipo de cambio del dólar: referencia del BCCR que publica Hacienda
 * (https://api.hacienda.go.cr/indicadores/tc/dolar, sin token). Se guarda uno por día.
 *
 * Seguridad: URL fija (no la decide ningún usuario, sin SSRF), timeouts cortos y validación
 * de la respuesta: fecha cercana a hoy y valores en un rango razonable (también hay CHECK en la BD).
 */
@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");
    static final BigDecimal MIN = new BigDecimal("100");
    static final BigDecimal MAX = new BigDecimal("2000");

    private final JdbcClient jdbc;
    private final RestClient http;
    private final boolean enabled;

    ExchangeRateService(JdbcClient jdbc,
                        @Value("${casaayg.exchange-rate.url}") String url,
                        @Value("${casaayg.exchange-rate.enabled}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HaciendaRate(Amount venta, Amount compra) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Amount(LocalDate fecha, BigDecimal valor) {}

    public record Rate(LocalDate date, BigDecimal buy, BigDecimal sell) {}

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 15 8,13,18 * * *", zone = "America/Costa_Rica")
    public void refresh() {
        if (!enabled) {
            return;
        }
        try {
            HaciendaRate rate = http.get().retrieve().body(HaciendaRate.class);
            store(validate(rate, LocalDate.now(COSTA_RICA)));
        } catch (RuntimeException e) {
            // Sin tipo de cambio nuevo se usa el último guardado; no es motivo para caerse.
            log.warn("No se pudo actualizar el tipo de cambio: {}", e.getClass().getSimpleName());
        }
    }

    static Rate validate(HaciendaRate rate, LocalDate today) {
        if (rate == null || rate.venta() == null || rate.compra() == null
                || rate.venta().fecha() == null || rate.venta().valor() == null || rate.compra().valor() == null) {
            throw new IllegalArgumentException("respuesta incompleta");
        }
        LocalDate date = rate.venta().fecha();
        if (date.isBefore(today.minusDays(7)) || date.isAfter(today.plusDays(1))) {
            throw new IllegalArgumentException("fecha fuera de rango");
        }
        for (BigDecimal v : new BigDecimal[] {rate.venta().valor(), rate.compra().valor()}) {
            if (v.compareTo(MIN) < 0 || v.compareTo(MAX) > 0) {
                throw new IllegalArgumentException("valor fuera de rango");
            }
        }
        return new Rate(date, rate.compra().valor(), rate.venta().valor());
    }

    @Transactional
    public void store(Rate rate) {
        jdbc.sql("""
                INSERT INTO exchange_rate (rate_date, buy, sell, source) VALUES (?, ?, ?, 'HACIENDA')
                ON CONFLICT (rate_date) DO UPDATE SET buy = EXCLUDED.buy, sell = EXCLUDED.sell,
                    source = EXCLUDED.source, fetched_at = now()""")
            .params(rate.date(), rate.buy(), rate.sell()).update();
    }

    @Transactional(readOnly = true)
    public Optional<Rate> latest() {
        return jdbc.sql("SELECT rate_date, buy, sell FROM exchange_rate ORDER BY rate_date DESC LIMIT 1")
            .query((rs, n) -> new Rate(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getBigDecimal(3)))
            .optional();
    }
}

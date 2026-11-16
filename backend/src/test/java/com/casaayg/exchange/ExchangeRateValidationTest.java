package com.casaayg.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.casaayg.exchange.ExchangeRateService.Amount;
import com.casaayg.exchange.ExchangeRateService.HaciendaRate;

/** Una respuesta manipulada o rara de la API de Hacienda no entra a la BD. */
class ExchangeRateValidationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void unaRespuestaNormalSeAcepta() {
        var rate = ExchangeRateService.validate(rate(TODAY, "448.34", "442.56"), TODAY);
        assertThat(rate.sell()).isEqualByComparingTo("448.34");
        assertThat(rate.buy()).isEqualByComparingTo("442.56");
    }

    @Test
    void valoresAbsurdosSeRechazan() {
        assertThatThrownBy(() -> ExchangeRateService.validate(rate(TODAY, "1", "1"), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExchangeRateService.validate(rate(TODAY, "99999", "448"), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fechasViejasOFuturasSeRechazan() {
        assertThatThrownBy(() -> ExchangeRateService.validate(rate(TODAY.minusDays(30), "448", "442"), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExchangeRateService.validate(rate(TODAY.plusDays(5), "448", "442"), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void respuestasIncompletasSeRechazan() {
        assertThatThrownBy(() -> ExchangeRateService.validate(new HaciendaRate(null, null), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExchangeRateService.validate(null, TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static HaciendaRate rate(LocalDate date, String sell, String buy) {
        return new HaciendaRate(new Amount(date, new BigDecimal(sell)), new Amount(date, new BigDecimal(buy)));
    }
}

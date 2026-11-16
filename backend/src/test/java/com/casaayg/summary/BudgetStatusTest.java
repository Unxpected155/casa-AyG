package com.casaayg.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Qué estado ve la pareja según lo gastado contra lo planeado (en colones). */
class BudgetStatusTest {

    @ParameterizedTest(name = "planeado {0}, gastado {1} → {2}")
    @CsvSource({
        "350000, 0, OK",
        "350000, 314999.99, OK",
        "350000, 315000, CERCA",
        "350000, 349999.99, CERCA",
        "350000, 350000, AL_LIMITE",
        "350000, 350000.00, AL_LIMITE",
        "350000, 350000.01, EXCEDIDO",
        "0, 1000, SIN_PRESUPUESTO",
    })
    void estado(String planned, String spent, String expected) {
        assertThat(SummaryService.status(new BigDecimal(planned), new BigDecimal(spent))).isEqualTo(expected);
    }
}

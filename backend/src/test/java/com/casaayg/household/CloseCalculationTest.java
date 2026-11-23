package com.casaayg.household;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

/** El bono es un % de lo que queda DESPUÉS de gastos y ahorros (decisión de la pareja). */
class CloseCalculationTest {

    @Test
    void loAhorradoSaleAntesDelBono() {
        HouseholdService.ClosePreview p = HouseholdService.calculate(YearMonth.of(2026, 9),
            new BigDecimal("1000000"), new BigDecimal("300000"), new BigDecimal("200000"),
            new BigDecimal("30000"), new BigDecimal("2.5"), 2, null);
        assertThat(p.surplus()).isEqualByComparingTo("500000");
        assertThat(p.bonus()).isEqualByComparingTo("12500");
        assertThat(p.perPerson()).isEqualByComparingTo("42500");
    }
}

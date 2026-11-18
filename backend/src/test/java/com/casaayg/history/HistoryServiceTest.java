package com.casaayg.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.casaayg.history.HistoryService.Cell;
import com.casaayg.history.HistoryService.CategoryTrend;
import com.casaayg.history.HistoryService.History;

/** El cálculo de Historia con datos fijos: promedio, mes en curso y meses sin datos. */
class HistoryServiceTest {

    static final UUID SALIDAS = UUID.randomUUID();
    static final UUID SUPER = UUID.randomUUID();
    static final UUID SALARIO = UUID.randomUUID();
    static final YearMonth NOV = YearMonth.of(2026, 11);

    static Cell cell(YearMonth m, UUID id, String name, String group, String amount) {
        return new Cell(m, id, name, group, new BigDecimal(amount), new BigDecimal(amount));
    }

    static Cell cell(YearMonth m, UUID id, String name, String group, String amount, String toDay) {
        return new Cell(m, id, name, group, new BigDecimal(amount), new BigDecimal(toDay));
    }

    @Test
    void comparaConElPromedioDeLos3MesesAnteriores() {
        List<Cell> cells = List.of(
            cell(NOV.minusMonths(3), SALIDAS, "Salidas", "DESEO", "50000"),
            cell(NOV.minusMonths(2), SALIDAS, "Salidas", "DESEO", "60000"),
            cell(NOV.minusMonths(1), SALIDAS, "Salidas", "DESEO", "55000"),
            cell(NOV, SALIDAS, "Salidas", "DESEO", "85000"),
            cell(NOV, SALARIO, "Salario", "INGRESO", "1000000"),
            cell(NOV.minusMonths(1), SALARIO, "Salario", "INGRESO", "1000000"));
        History h = HistoryService.build(NOV, LocalDate.of(2026, 12, 2), cells);

        assertThat(h.partial()).isFalse();
        assertThat(h.baselineMonths()).isEqualTo(3);
        CategoryTrend salidas = h.categories().stream().filter(c -> c.categoryId().equals(SALIDAS)).findFirst().orElseThrow();
        assertThat(salidas.average()).isEqualByComparingTo("55000");
        assertThat(salidas.delta()).isEqualByComparingTo("30000");
        assertThat(salidas.deltaPct()).isEqualTo(55);
        assertThat(salidas.series()).hasSize(6);

        var nov = h.months().get(5);
        assertThat(nov.income()).isEqualByComparingTo("1000000");
        assertThat(nov.expenses()).isEqualByComparingTo("85000");
        assertThat(nov.savingsRate()).isEqualTo(92);
    }

    @Test
    void enElMesEnCursoComparaConLoQueSeLlevabaAlMismoDia() {
        UUID renta = UUID.randomUUID();
        List<Cell> cells = List.of(
            // Octubre: súper 300 000 en el mes, 140 000 hasta el día 15; el alquiler, todo el día 2.
            cell(NOV.minusMonths(1), SUPER, "Súper", "NECESIDAD", "300000", "140000"),
            cell(NOV.minusMonths(1), renta, "Vivienda", "NECESIDAD", "350000", "350000"),
            cell(NOV, SUPER, "Súper", "NECESIDAD", "100000"),
            cell(NOV, renta, "Vivienda", "NECESIDAD", "350000"));
        History h = HistoryService.build(NOV, LocalDate.of(2026, 11, 15), cells);
        assertThat(h.partial()).isTrue();
        CategoryTrend s = h.categories().stream().filter(c -> c.categoryId().equals(SUPER)).findFirst().orElseThrow();
        assertThat(s.expected()).isEqualByComparingTo("140000");
        assertThat(s.delta()).isEqualByComparingTo("-40000");
        // El alquiler pagado completo al inicio NO aparece como "gastaste de más".
        CategoryTrend v = h.categories().stream().filter(c -> c.categoryId().equals(renta)).findFirst().orElseThrow();
        assertThat(v.delta()).isEqualByComparingTo("0");
    }

    @Test
    void sinMesesAnterioresNoHayComparacionYLosMesesSinDatosNoCuentanComoCero() {
        History empty = HistoryService.build(NOV, LocalDate.of(2026, 11, 20),
            List.of(cell(NOV, SUPER, "Súper", "NECESIDAD", "1000")));
        assertThat(empty.baselineMonths()).isZero();
        assertThat(empty.categories().get(0).average()).isNull();

        // Solo octubre tiene datos: el promedio es octubre, no octubre/3.
        History one = HistoryService.build(NOV, LocalDate.of(2026, 12, 1), List.of(
            cell(NOV.minusMonths(1), SUPER, "Súper", "NECESIDAD", "90000"),
            cell(NOV, SUPER, "Súper", "NECESIDAD", "90000")));
        assertThat(one.baselineMonths()).isEqualTo(1);
        assertThat(one.categories().get(0).average()).isEqualByComparingTo("90000");
        assertThat(one.categories().get(0).delta()).isEqualByComparingTo("0");
    }
}

package com.casaayg.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.casaayg.goal.PlannerService.Allocation;
import com.casaayg.goal.PlannerService.Input;

/** El reparto del planeador, sin BD: primero las fechas, luego la cascada por prioridad. */
class PlannerCalculationTest {

    static final YearMonth NOV = YearMonth.of(2026, 11);
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    @Test
    void primeroLoQueNecesitaLaMetaConFechaYLoQueSobraVaEnCascada() {
        // A: ₡120 000 para abril (6 meses contando noviembre). B: ₡300 000 sin fecha. Apartan ₡50 000.
        List<Allocation> r = PlannerService.simulate(List.of(
            new Input(a, new BigDecimal("120000"), NOV.plusMonths(5)),
            new Input(b, new BigDecimal("300000"), null)), new BigDecimal("50000"), NOV);
        // A recibe lo que necesita (₡20 000) y, como es la más importante sin terminar, también lo
        // que sobra: termina en enero, antes de su fecha. Después todo va a B.
        assertThat(r.get(0).firstMonthCrc()).isEqualByComparingTo("50000");
        assertThat(r.get(1).firstMonthCrc()).isEqualByComparingTo("0");
        assertThat(r.get(0).eta()).isEqualTo(NOV.plusMonths(2));
        // B: ₡30 000 en enero y después ₡50 000 al mes → llega en agosto.
        assertThat(r.get(1).eta()).isEqualTo(NOV.plusMonths(8));
    }

    @Test
    void sinFechasEsCascadaPuraYUnaMetaTerminadaLiberaSuParte() {
        List<Allocation> r = PlannerService.simulate(List.of(
            new Input(a, new BigDecimal("100000"), null),
            new Input(b, new BigDecimal("100000"), null)), new BigDecimal("60000"), NOV);
        assertThat(r.get(0).firstMonthCrc()).isEqualByComparingTo("60000");
        assertThat(r.get(1).firstMonthCrc()).isEqualByComparingTo("0");
        assertThat(r.get(0).eta()).isEqualTo(NOV.plusMonths(1));
        // Diciembre: ₡40 000 a A y ₡20 000 a B; enero ₡60 000; febrero los ₡20 000 que faltan.
        assertThat(r.get(1).eta()).isEqualTo(NOV.plusMonths(3));
    }

    @Test
    void sinApartarNadaNoSeLlegaYUnaMetaCompletaYaLlego() {
        List<Allocation> r = PlannerService.simulate(List.of(
            new Input(a, new BigDecimal("100000"), null),
            new Input(b, BigDecimal.ZERO, null)), BigDecimal.ZERO, NOV);
        assertThat(r.get(0).eta()).isNull();
        assertThat(r.get(1).eta()).isEqualTo(NOV);
    }

    @Test
    void laRecomendacionEsElMinimoParaLlegarATiempoATodasLasMetasConFecha() {
        Input boda = new Input(a, new BigDecimal("120000"), NOV.plusMonths(5));
        Input viaje = new Input(b, new BigDecimal("300000"), null);
        assertThat(PlannerService.recommended(List.of(boda, viaje), NOV)).isEqualByComparingTo("20000");
        assertThat(PlannerService.bindingGoal(List.of(boda, viaje), new BigDecimal("20000"), NOV)).isEqualTo(boda);
        // Sin metas con fecha no hay mínimo: se recomienda el promedio de lo que sobró.
        assertThat(PlannerService.recommended(List.of(viaje), NOV)).isNull();

        // Dos con fecha: la más importante primero, pero las dos tienen que llegar.
        Input carro = new Input(UUID.randomUUID(), new BigDecimal("60000"), NOV.plusMonths(1));
        BigDecimal both = PlannerService.recommended(List.of(boda, carro), NOV);
        List<Allocation> r = PlannerService.simulate(List.of(boda, carro), both, NOV);
        assertThat(r.get(0).eta()).isBeforeOrEqualTo(boda.target());
        assertThat(r.get(1).eta()).isBeforeOrEqualTo(carro.target());
        List<Allocation> less = PlannerService.simulate(List.of(boda, carro), both.subtract(new BigDecimal("1000")), NOV);
        assertThat(less.get(0).eta().isAfter(boda.target()) || less.get(1).eta().isAfter(carro.target())).isTrue();
    }
}

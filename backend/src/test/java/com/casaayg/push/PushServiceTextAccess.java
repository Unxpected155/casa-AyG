package com.casaayg.push;

import java.time.LocalDate;
import java.util.List;

/** Acceso de prueba al texto de los avisos (el método es del paquete). */
public final class PushServiceTextAccess {

    private PushServiceTextAccess() {}

    public static String recurringText(LocalDate today, List<String[]> items) {
        return PushService.recurringText(today, items);
    }

    public static String debtText(LocalDate today, List<String[]> items) {
        return PushService.debtText(today, items);
    }

    /** Cada elemento: {categoría, nivel, es de la casa}. */
    public static String budgetText(List<Object[]> hits) {
        return PushService.budgetText(hits.stream()
            .map(h -> new PushService.BudgetHit((String) h[0], (Integer) h[1], (Boolean) h[2])).toList());
    }

    public static String closeReminderText(java.time.YearMonth month) {
        return PushService.closeReminderText(month);
    }
}

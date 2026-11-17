package com.casaayg.push;

import java.time.LocalDate;
import java.util.List;

/** Acceso de prueba al texto de los avisos (el método es del paquete). */
public final class PushServiceTextAccess {

    private PushServiceTextAccess() {}

    public static String recurringText(LocalDate today, List<String[]> items) {
        return PushService.recurringText(today, items);
    }
}

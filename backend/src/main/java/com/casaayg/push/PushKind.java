package com.casaayg.push;

/**
 * Los tipos de aviso (PRODUCT.md, «Notificaciones push»). Cada persona enciende y apaga cada
 * uno, menos {@link #SEGURIDAD}, que además suena a cualquier hora y no tiene tope diario.
 * Fuera de la lista a propósito: cada movimiento que anota la otra persona.
 */
public enum PushKind {
    /** Una passkey nueva o un código de recuperación usado. */
    SEGURIDAD,
    /** Pagos fijos y cuotas de deudas que vencen pronto. */
    POR_PAGAR,
    /** Un CDP vence mañana o hoy. */
    CDP,
    /** Una categoría llegó al 80 % o al 100 % de lo planeado. */
    PRESUPUESTO,
    /** La casa: el mes por cerrar, lo que hizo la pareja con lo tuyo, unir los espacios. */
    CASA,
    /** Resumen diario de facturas nuevas por revisar. */
    FACTURAS,
    /** Recordatorio quincenal si hay diezmo por dar. */
    DIEZMO,
    /** Una meta llegó al 25, 50, 75 o 100 %. */
    METAS;

    /** Se puede apagar, respeta las horas de silencio y el tope de uno al día. */
    public boolean optional() {
        return this != SEGURIDAD;
    }
}

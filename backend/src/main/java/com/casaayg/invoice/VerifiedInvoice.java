package com.casaayg.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que se saca de una factura que pasó las verificaciones. {@code receptorId} solo vive en
 * memoria para buscar a su dueño (TaxIdService); nunca se guarda, y en un tiquete suele ser null.
 * {@code signatureVerified}: las firmas encadenan a una autoridad de confianza y la respuesta la
 * firmó Hacienda; si no, {@code diagnostic} trae los datos públicos de quién firmó.
 * {@code haciendaConfirmed}: llegó con la respuesta «aceptado» de Hacienda (solo un tiquete puede
 * no tenerla).
 */
public record VerifiedInvoice(Kind kind, String clave, LocalDate date, String merchant, String emisorId,
                              String receptorId, String currency, BigDecimal total, boolean signatureVerified,
                              String diagnostic, boolean haciendaConfirmed) {

    public enum Kind { FACTURA, NOTA_CREDITO, TIQUETE }

    /** Una factura con la firma confirmada contra una autoridad de confianza. */
    public VerifiedInvoice(Kind kind, String clave, LocalDate date, String merchant, String emisorId,
                           String receptorId, String currency, BigDecimal total) {
        this(kind, clave, date, merchant, emisorId, receptorId, currency, total, true, null, true);
    }
}

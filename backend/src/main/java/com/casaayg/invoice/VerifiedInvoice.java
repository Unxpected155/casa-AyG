package com.casaayg.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que se saca de una factura que pasó TODAS las verificaciones. {@code receptorId} solo
 * vive en memoria para buscar a su dueño (TaxIdService); nunca se guarda.
 */
public record VerifiedInvoice(Kind kind, String clave, LocalDate date, String merchant, String emisorId,
                              String receptorId, String currency, BigDecimal total) {

    public enum Kind { FACTURA, NOTA_CREDITO }
}

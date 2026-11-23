package com.casaayg.invoice;

/**
 * Una factura que no pasó la verificación. El motivo es corto y nunca lleva datos del XML.
 * {@code detail}: solo datos públicos de la firma (la autoridad que emitió el certificado y el
 * SERIALNUMBER del firmante, que es un comercio o Hacienda) para poder configurar las
 * autoridades de confianza. Nunca la cédula del receptor.
 */
public final class InvoiceRejected extends Exception {

    private final String detail;

    public InvoiceRejected(String reason) {
        this(reason, null);
    }

    public InvoiceRejected(String reason, String detail) {
        super(reason, null, false, false);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}

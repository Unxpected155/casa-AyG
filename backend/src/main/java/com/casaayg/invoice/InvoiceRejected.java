package com.casaayg.invoice;

/** Una factura que no pasó la verificación. El motivo es corto y nunca lleva datos del XML. */
public final class InvoiceRejected extends Exception {

    public InvoiceRejected(String reason) {
        super(reason, null, false, false);
    }
}

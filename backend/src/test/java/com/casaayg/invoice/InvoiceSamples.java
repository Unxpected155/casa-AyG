package com.casaayg.invoice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Facturas y respuestas de Hacienda sintéticas (cédulas ficticias), sin firmar. */
public final class InvoiceSamples {

    public static final String EMISOR = "3101999999";
    public static final String HACIENDA = "2100099999";

    private InvoiceSamples() {}

    public static String consecutivo(int n) {
        return "0010000101" + String.format("%010d", n);
    }

    public static String clave(String emisor, int n) {
        String fecha = now().format(DateTimeFormatter.ofPattern("ddMMyy"));
        return "506" + fecha + "0".repeat(12 - emisor.length()) + emisor + consecutivo(n) + "1" + "12345678";
    }

    public static String factura(int n, String receptor, String total) {
        return """
            <FacturaElectronica xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica"><Clave>%s</Clave><NumeroConsecutivo>%s</NumeroConsecutivo><FechaEmision>%s</FechaEmision><Emisor><Nombre>Comercio de Prueba S.A.</Nombre><Identificacion><Tipo>02</Tipo><Numero>%s</Numero></Identificacion><NombreComercial>Super Prueba</NombreComercial></Emisor><Receptor><Nombre>Persona de Prueba</Nombre><Identificacion><Tipo>01</Tipo><Numero>%s</Numero></Identificacion></Receptor><ResumenFactura><CodigoTipoMoneda><CodigoMoneda>CRC</CodigoMoneda><TipoCambio>1</TipoCambio></CodigoTipoMoneda><TotalComprobante>%s</TotalComprobante></ResumenFactura></FacturaElectronica>"""
            .formatted(clave(EMISOR, n), consecutivo(n), now().minusHours(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                EMISOR, receptor, total);
    }

    public static String mensaje(int n, String receptor, String total) {
        return """
            <MensajeHacienda xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/mensajeHacienda"><Clave>%s</Clave><NombreEmisor>Comercio de Prueba S.A.</NombreEmisor><TipoIdentificacionEmisor>02</TipoIdentificacionEmisor><NumeroCedulaEmisor>%s</NumeroCedulaEmisor><NombreReceptor>Persona de Prueba</NombreReceptor><TipoIdentificacionReceptor>01</TipoIdentificacionReceptor><NumeroCedulaReceptor>%s</NumeroCedulaReceptor><Mensaje>1</Mensaje><DetalleMensaje>Aceptado</DetalleMensaje><MontoTotalImpuesto>0</MontoTotalImpuesto><TotalFactura>%s</TotalFactura></MensajeHacienda>"""
            .formatted(clave(EMISOR, n), EMISOR, receptor, total);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(-6)).withNano(0);
    }
}

package com.casaayg.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * La suite de facturas maliciosas (Fase 8). Todo es sintético: cédulas ficticias y
 * certificados de prueba. Ninguna factura falsificada debe pasar.
 */
class InvoiceVerifierTest {

    static final String EMISOR = "3101999999";
    static final String RECEPTOR = "199999999";
    static final String HACIENDA = "2100099999";
    static final String CONSECUTIVO = "00100001010000000123";

    static TestPki.Signer ca;
    static TestPki.Signer comercio;
    static TestPki.Signer otroComercio;
    static TestPki.Signer hacienda;
    static TestPki.Signer comercioPirata;
    static TestPki.Signer soloCifrado;
    static InvoiceVerifier verifier;

    @BeforeAll
    static void pki() throws Exception {
        TestPki pki = new TestPki();
        ca = pki.authority("raiz");
        comercio = pki.issue(ca, "raiz", "comercio", "CPJ-" + EMISOR, "digitalSignature,nonRepudiation");
        otroComercio = pki.issue(ca, "raiz", "otro", "CPJ-3101888888", "digitalSignature,nonRepudiation");
        hacienda = pki.issue(ca, "raiz", "hacienda", "CPJ-" + HACIENDA, "digitalSignature,nonRepudiation");
        soloCifrado = pki.issue(ca, "raiz", "cifrado", "CPJ-" + EMISOR, "keyEncipherment");
        TestPki.Signer pirata = pki.authority("pirata");
        comercioPirata = pki.issue(pirata, "pirata", "comerciopirata", "CPJ-" + EMISOR, "digitalSignature");
        verifier = new InvoiceVerifier(List.of(ca.cert()), HACIENDA, Clock.systemUTC());
    }

    // --- lo que sí pasa ---------------------------------------------------------------------

    @Test
    void unaFacturaFirmadaYAceptadaPasa() throws Exception {
        VerifiedInvoice v = verifier.verify(TestPki.sign(factura(), comercio), TestPki.sign(mensaje(), hacienda));
        assertThat(v.kind()).isEqualTo(VerifiedInvoice.Kind.FACTURA);
        assertThat(v.clave()).isEqualTo(clave(EMISOR));
        assertThat(v.merchant()).isEqualTo("Super Prueba");
        assertThat(v.currency()).isEqualTo("CRC");
        assertThat(v.total()).isEqualByComparingTo("11300.00");
        assertThat(v.receptorId()).isEqualTo(RECEPTOR);
    }

    @Test
    void unaNotaDeCreditoPasaComoNotaDeCredito() throws Exception {
        String nota = factura().replace("FacturaElectronica", "NotaCreditoElectronica")
            .replace("facturaElectronica", "notaCreditoElectronica");
        VerifiedInvoice v = verifier.verify(TestPki.sign(nota, comercio), TestPki.sign(mensaje(), hacienda));
        assertThat(v.kind()).isEqualTo(VerifiedInvoice.Kind.NOTA_CREDITO);
    }

    @Test
    void elNombreDelComercioSeLimpia() throws Exception {
        String xml = factura().replace("<NombreComercial>Super Prueba</NombreComercial>",
            "<NombreComercial>  Super\t\n   <![CDATA[<script>]]>‮abc</NombreComercial>");
        VerifiedInvoice v = verifier.verify(TestPki.sign(xml, comercio), TestPki.sign(mensaje(), hacienda));
        // Se guarda como texto (React lo escapa); sin controles ni caracteres invisibles.
        assertThat(v.merchant()).isEqualTo("Super <script> abc");
    }

    // --- falsificaciones --------------------------------------------------------------------

    @Test
    void cambiarElMontoDespuesDeFirmarSeRechaza() throws Exception {
        byte[] signed = TestPki.sign(factura(), comercio);
        byte[] tampered = new String(signed, StandardCharsets.UTF_8).replace("11300.00000", "1130.00000")
            .getBytes(StandardCharsets.UTF_8);
        rejected(tampered, TestPki.sign(mensaje().replace("11300.00000", "1130.00000"), hacienda), "firma");
    }

    @Test
    void sinFirmaSeRechaza() throws Exception {
        rejected(factura().getBytes(StandardCharsets.UTF_8), TestPki.sign(mensaje(), hacienda), "firma");
    }

    @Test
    void unaAutoridadQueNoEsDeConfianzaSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), comercioPirata), TestPki.sign(mensaje(), hacienda), "firma");
    }

    @Test
    void firmadaPorOtraEmpresaSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), otroComercio), TestPki.sign(mensaje(), hacienda), "firmante");
    }

    @Test
    void unCertificadoQueNoEsParaFirmarSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), soloCifrado), TestPki.sign(mensaje(), hacienda), "firma");
    }

    @Test
    void unaFirmaQueNoCubreTodoElDocumentoSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), comercio, "#clave"), TestPki.sign(mensaje(), hacienda), "firma");
    }

    @Test
    void dosFirmasSeRechaza() throws Exception {
        String once = new String(TestPki.sign(factura(), comercio), StandardCharsets.UTF_8);
        String sig = once.substring(once.indexOf("<ds:Signature"), once.indexOf("</ds:Signature>") + 15);
        String twice = once.replace("</FacturaElectronica>", sig + "</FacturaElectronica>");
        rejected(twice.getBytes(StandardCharsets.UTF_8), TestPki.sign(mensaje(), hacienda), "firma");
    }

    @Test
    void laClaveTieneQueSerDelEmisor() throws Exception {
        String xml = factura().replace("<Clave>" + clave(EMISOR), "<Clave>" + clave("3101888888"));
        rejected(TestPki.sign(xml, comercio), TestPki.sign(mensaje().replace(clave(EMISOR), clave("3101888888")),
            hacienda), "clave");
    }

    @Test
    void unTiqueteOUnXmlCualquieraSeRechaza() throws Exception {
        String tiquete = factura().replace("FacturaElectronica", "TiqueteElectronico")
            .replace("facturaElectronica", "tiqueteElectronico");
        rejected(TestPki.sign(tiquete, comercio), TestPki.sign(mensaje(), hacienda), "tipo");
        String otroEsquema = factura().replace("https://cdn.comprobanteselectronicos.go.cr/", "https://evil.example/");
        rejected(TestPki.sign(otroEsquema, comercio), TestPki.sign(mensaje(), hacienda), "esquema");
    }

    @Test
    void sinReceptorSeRechaza() throws Exception {
        String xml = factura().replaceAll("<Receptor>.*</Receptor>", "");
        rejected(TestPki.sign(xml, comercio), TestPki.sign(mensaje(), hacienda), "sin-receptor");
    }

    @Test
    void camposRepetidosSeRechazan() throws Exception {
        String xml = factura().replace("<TotalComprobante>11300.00000</TotalComprobante>",
            "<TotalComprobante>11300.00000</TotalComprobante><TotalComprobante>1.00000</TotalComprobante>");
        rejected(TestPki.sign(xml, comercio), TestPki.sign(mensaje(), hacienda), "repetido-TotalComprobante");
    }

    @Test
    void montosAbsurdosSeRechazan() throws Exception {
        for (String monto : List.of("0", "-5", "1e9", "99999999999999", "12,50")) {
            String xml = factura().replace("11300.00000", monto);
            rejected(TestPki.sign(xml, comercio), TestPki.sign(mensaje().replace("11300.00000", monto), hacienda),
                "monto");
        }
    }

    @Test
    void otraMonedaSeRechaza() throws Exception {
        String xml = factura().replace("<CodigoMoneda>CRC</CodigoMoneda>", "<CodigoMoneda>EUR</CodigoMoneda>");
        rejected(TestPki.sign(xml, comercio), TestPki.sign(mensaje(), hacienda), "moneda");
    }

    @Test
    void unaFechaMuyViejaOFuturaSeRechaza() throws Exception {
        for (OffsetDateTime when : List.of(now().minusDays(500), now().plusDays(3))) {
            String xml = factura().replace(fecha(now().minusHours(1)), fecha(when));
            rejected(TestPki.sign(xml, comercio), TestPki.sign(mensaje(), hacienda), "fecha");
        }
    }

    // --- la respuesta de Hacienda ------------------------------------------------------------

    @Test
    void sinMensajeDeHaciendaSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), comercio), TestPki.sign(factura(), comercio), "hacienda");
    }

    @Test
    void haciendaRechazadaSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), comercio),
            TestPki.sign(mensaje().replace("<Mensaje>1</Mensaje>", "<Mensaje>3</Mensaje>"), hacienda),
            "hacienda-no-aceptada");
    }

    @Test
    void unMensajeFirmadoPorElComercioYNoPorHaciendaSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), comercio), TestPki.sign(mensaje(), comercio), "hacienda-firmante");
    }

    @Test
    void unMensajeDeOtraFacturaSeRechaza() throws Exception {
        String otra = mensaje().replace(clave(EMISOR), clave(EMISOR).substring(0, 49) + "7");
        rejected(TestPki.sign(factura(), comercio), TestPki.sign(otra, hacienda), "hacienda-clave");
    }

    @Test
    void unMensajeConOtroReceptorOTotalSeRechaza() throws Exception {
        rejected(TestPki.sign(factura(), comercio),
            TestPki.sign(mensaje().replace("<NumeroCedulaReceptor>" + RECEPTOR, "<NumeroCedulaReceptor>188888888"),
                hacienda), "hacienda-receptor");
        rejected(TestPki.sign(factura(), comercio),
            TestPki.sign(mensaje().replace("<TotalFactura>11300.00000", "<TotalFactura>11.00000"), hacienda),
            "hacienda-total");
    }

    // --- XML hostil -----------------------------------------------------------------------------

    @Test
    void xxeYDtdSeRechazanSinLeerArchivos() {
        String xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE f [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <FacturaElectronica xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica">
            <Clave>&x;</Clave></FacturaElectronica>""";
        rejected(xxe.getBytes(StandardCharsets.UTF_8), new byte[] {1}, "xml");
        String lol = """
            <?xml version="1.0"?>
            <!DOCTYPE l [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>
            <l>&b;</l>""";
        rejected(lol.getBytes(StandardCharsets.UTF_8), new byte[] {1}, "xml");
    }

    @Test
    void unArchivoGiganteOVacioSeRechaza() {
        rejected(new byte[SafeXml.MAX_BYTES + 1], new byte[] {1}, "tamaño");
        rejected(new byte[0], new byte[] {1}, "tamaño");
        rejected("no es xml".getBytes(StandardCharsets.UTF_8), new byte[] {1}, "xml");
    }

    @Test
    void sinAutoridadesConfiguradasNadaPasa() throws Exception {
        InvoiceVerifier empty = new InvoiceVerifier(List.of(), HACIENDA, Clock.systemUTC());
        assertThatThrownBy(() -> empty.verify(TestPki.sign(factura(), comercio), TestPki.sign(mensaje(), hacienda)))
            .isInstanceOf(InvoiceRejected.class).hasMessage("sin-configurar");
    }

    // --- utilidades -----------------------------------------------------------------------------

    private static void rejected(byte[] invoice, byte[] message, String reason) {
        assertThatThrownBy(() -> verifier.verify(invoice, message))
            .isInstanceOf(InvoiceRejected.class).hasMessage(reason);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.ofHours(-6)).withNano(0);
    }

    static String fecha(OffsetDateTime t) {
        return t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String clave(String emisor) {
        String fecha = now().format(DateTimeFormatter.ofPattern("ddMMyy"));
        return "506" + fecha + "0".repeat(12 - emisor.length()) + emisor + CONSECUTIVO + "1" + "12345678";
    }

    static String factura() {
        return """
            <FacturaElectronica xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica"><Clave>%s</Clave><NumeroConsecutivo>%s</NumeroConsecutivo><FechaEmision>%s</FechaEmision><Emisor><Nombre>Comercio de Prueba S.A.</Nombre><Identificacion><Tipo>02</Tipo><Numero>%s</Numero></Identificacion><NombreComercial>Super Prueba</NombreComercial></Emisor><Receptor><Nombre>Persona de Prueba</Nombre><Identificacion><Tipo>01</Tipo><Numero>%s</Numero></Identificacion></Receptor><ResumenFactura><CodigoTipoMoneda><CodigoMoneda>CRC</CodigoMoneda><TipoCambio>1</TipoCambio></CodigoTipoMoneda><TotalComprobante>11300.00000</TotalComprobante></ResumenFactura></FacturaElectronica>"""
            .formatted(clave(EMISOR), CONSECUTIVO, fecha(now().minusHours(1)), EMISOR, RECEPTOR);
    }

    static String mensaje() {
        return """
            <MensajeHacienda xmlns="https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/mensajeHacienda"><Clave>%s</Clave><NombreEmisor>Comercio de Prueba S.A.</NombreEmisor><TipoIdentificacionEmisor>02</TipoIdentificacionEmisor><NumeroCedulaEmisor>%s</NumeroCedulaEmisor><NombreReceptor>Persona de Prueba</NombreReceptor><TipoIdentificacionReceptor>01</TipoIdentificacionReceptor><NumeroCedulaReceptor>%s</NumeroCedulaReceptor><Mensaje>1</Mensaje><DetalleMensaje>Aceptado</DetalleMensaje><MontoTotalImpuesto>1300.00000</MontoTotalImpuesto><TotalFactura>11300.00000</TotalFactura></MensajeHacienda>"""
            .formatted(clave(EMISOR), EMISOR, RECEPTOR);
    }
}

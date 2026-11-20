package com.casaayg.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Key;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Verifica una factura electrónica de Hacienda (ADR-006) junto con la respuesta de Hacienda.
 * Una factura pasa solo si TODO esto se cumple:
 * <ol>
 *   <li>Es FacturaElectronica o NotaCreditoElectronica, del esquema de Hacienda.</li>
 *   <li>Tiene UNA firma, hija de la raíz, que firma el documento COMPLETO (sin "wrapping"),
 *       con un certificado que encadena a una autoridad de confianza y que es del emisor.</li>
 *   <li>La clave de 50 dígitos coincide con el emisor y el consecutivo.</li>
 *   <li>El MensajeHacienda está firmado por Hacienda, es de la misma clave, del mismo emisor
 *       y receptor, por el mismo total, y dice ACEPTADO.</li>
 * </ol>
 * Que el receptor sea alguien de la casa lo decide quien llama (TaxIdService).
 */
public final class InvoiceVerifier {

    private static final String NS_PREFIX = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/";
    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final BigDecimal MAX_TOTAL = new BigDecimal("1000000000");
    private static final Set<String> SIGNATURE_ALGORITHMS = Set.of(
        SignatureMethod.RSA_SHA256, SignatureMethod.RSA_SHA384, SignatureMethod.RSA_SHA512,
        SignatureMethod.ECDSA_SHA256, SignatureMethod.ECDSA_SHA384, SignatureMethod.ECDSA_SHA512);

    private final Set<TrustAnchor> anchors;
    private final String haciendaId;
    private final Clock clock;

    /**
     * @param trusted    autoridades que emiten las llaves de firma de los comprobantes
     * @param haciendaId cédula jurídica que va en el certificado con que firma Hacienda
     */
    public InvoiceVerifier(List<X509Certificate> trusted, String haciendaId, Clock clock) {
        Set<TrustAnchor> set = new HashSet<>();
        trusted.forEach(c -> set.add(new TrustAnchor(c, null)));
        this.anchors = Set.copyOf(set);
        this.haciendaId = stripZeros(haciendaId == null ? "" : digits(haciendaId));
        this.clock = clock;
    }

    public VerifiedInvoice verify(byte[] invoiceXml, byte[] haciendaXml) throws InvoiceRejected {
        if (anchors.isEmpty() || haciendaId.isEmpty()) {
            throw new InvoiceRejected("sin-configurar");
        }
        Document doc = SafeXml.parse(invoiceXml);
        Element root = doc.getDocumentElement();
        VerifiedInvoice.Kind kind = switch (String.valueOf(root.getLocalName())) {
            case "FacturaElectronica" -> VerifiedInvoice.Kind.FACTURA;
            case "NotaCreditoElectronica" -> VerifiedInvoice.Kind.NOTA_CREDITO;
            default -> throw new InvoiceRejected("tipo");
        };
        requireNamespace(root, kind == VerifiedInvoice.Kind.FACTURA ? "facturaElectronica" : "notaCreditoElectronica");

        String clave = text(root, "Clave");
        String consecutivo = text(root, "NumeroConsecutivo");
        if (!clave.matches("506[0-9]{47}") || !consecutivo.matches("[0-9]{20}")) {
            throw new InvoiceRejected("clave");
        }
        Instant issued;
        try {
            issued = OffsetDateTime.parse(text(root, "FechaEmision")).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvoiceRejected("fecha");
        }
        Instant now = clock.instant();
        if (issued.isAfter(now.plusSeconds(86_400)) || issued.isBefore(now.minusSeconds(400L * 86_400))) {
            throw new InvoiceRejected("fecha");
        }

        Element emisor = only(root, "Emisor");
        String emisorId = stripZeros(digits(text(only(emisor, "Identificacion"), "Numero")));
        Element receptor = optional(root, "Receptor");
        if (receptor == null) {
            throw new InvoiceRejected("sin-receptor");
        }
        String receptorId = stripZeros(digits(text(only(receptor, "Identificacion"), "Numero")));
        if (emisorId.isEmpty() || receptorId.isEmpty()) {
            throw new InvoiceRejected("identificacion");
        }
        // Clave: 506 + ddmmaa + cédula del emisor (12) + consecutivo (20) + situación + seguridad.
        if (!stripZeros(clave.substring(9, 21)).equals(emisorId) || !clave.substring(21, 41).equals(consecutivo)) {
            throw new InvoiceRejected("clave");
        }

        Element resumen = only(root, "ResumenFactura");
        Element moneda = optional(resumen, "CodigoTipoMoneda");
        String currency = moneda == null ? "CRC" : text(moneda, "CodigoMoneda");
        if (!currency.equals("CRC") && !currency.equals("USD")) {
            throw new InvoiceRejected("moneda");
        }
        BigDecimal total = amount(text(resumen, "TotalComprobante"));

        Element nombreComercial = optional(emisor, "NombreComercial");
        String merchant = clean(nombreComercial != null ? nombreComercial.getTextContent() : text(emisor, "Nombre"));
        if (merchant.isEmpty()) {
            throw new InvoiceRejected("emisor");
        }

        X509Certificate signer = verifySignature(doc, Date.from(issued));
        if (!signerId(signer).equals(emisorId)) {
            throw new InvoiceRejected("firmante");
        }

        verifyHacienda(haciendaXml, clave, emisorId, receptorId, total, issued);

        return new VerifiedInvoice(kind, clave, issued.atZone(CR).toLocalDate(), merchant, emisorId, receptorId,
            currency, total.setScale(2, RoundingMode.HALF_UP));
    }

    private void verifyHacienda(byte[] xml, String clave, String emisorId, String receptorId, BigDecimal total,
                                Instant issued) throws InvoiceRejected {
        Document doc = SafeXml.parse(xml);
        Element root = doc.getDocumentElement();
        if (!"MensajeHacienda".equals(root.getLocalName())) {
            throw new InvoiceRejected("hacienda");
        }
        requireNamespace(root, "mensajeHacienda");
        // Hacienda responde después de emitida la factura: su firma se valida a la fecha de hoy.
        Instant now = clock.instant();
        X509Certificate signer = verifySignature(doc, Date.from(now.isAfter(issued) ? now : issued));
        if (!signerId(signer).equals(haciendaId)) {
            throw new InvoiceRejected("hacienda-firmante");
        }
        if (!text(root, "Clave").equals(clave)) {
            throw new InvoiceRejected("hacienda-clave");
        }
        if (!text(root, "Mensaje").equals("1")) {
            throw new InvoiceRejected("hacienda-no-aceptada");
        }
        if (!stripZeros(digits(text(root, "NumeroCedulaEmisor"))).equals(emisorId)) {
            throw new InvoiceRejected("hacienda-emisor");
        }
        if (!stripZeros(digits(text(root, "NumeroCedulaReceptor"))).equals(receptorId)) {
            throw new InvoiceRejected("hacienda-receptor");
        }
        if (amount(text(root, "TotalFactura")).compareTo(total) != 0) {
            throw new InvoiceRejected("hacienda-total");
        }
    }

    // --- firma -------------------------------------------------------------------------------

    private X509Certificate verifySignature(Document doc, Date at) throws InvoiceRejected {
        Element root = doc.getDocumentElement();
        NodeList all = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (all.getLength() != 1 || all.item(0).getParentNode() != root) {
            throw new InvoiceRejected("firma");
        }
        registerIds(root);
        ChainKeySelector selector = new ChainKeySelector(anchors, at);
        try {
            DOMValidateContext ctx = new DOMValidateContext(selector, all.item(0));
            ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
            if (!SIGNATURE_ALGORITHMS.contains(signature.getSignedInfo().getSignatureMethod().getAlgorithm())) {
                throw new InvoiceRejected("firma-algoritmo");
            }
            // Debe firmar el documento completo; si no, se podría cambiar lo que no cubre.
            boolean whole = false;
            for (Object o : signature.getSignedInfo().getReferences()) {
                Reference r = (Reference) o;
                if ("".equals(r.getURI()) && r.getTransforms().stream()
                        .anyMatch(t -> Transform.ENVELOPED.equals(((Transform) t).getAlgorithm()))) {
                    whole = true;
                }
            }
            if (!whole || !signature.validate(ctx) || selector.signer == null) {
                throw new InvoiceRejected("firma");
            }
            return selector.signer;
        } catch (InvoiceRejected e) {
            throw e;
        } catch (Exception e) {
            throw new InvoiceRejected("firma");
        }
    }

    /** Los "Id" (XAdES SignedProperties) como atributos ID; repetidos = documento tramposo. */
    private static void registerIds(Element root) throws InvoiceRejected {
        Set<String> seen = new HashSet<>();
        List<Element> stack = new ArrayList<>(List.of(root));
        while (!stack.isEmpty()) {
            Element e = stack.removeLast();
            Attr id = e.getAttributeNode("Id");
            if (id != null) {
                if (!seen.add(id.getValue())) {
                    throw new InvoiceRejected("firma-ids");
                }
                e.setIdAttributeNode(id, true);
            }
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element child) {
                    stack.add(child);
                }
            }
        }
    }

    /** Toma el certificado del KeyInfo SOLO si encadena a una autoridad de confianza. */
    private static final class ChainKeySelector extends KeySelector {
        private final Set<TrustAnchor> anchors;
        private final Date at;
        X509Certificate signer;

        ChainKeySelector(Set<TrustAnchor> anchors, Date at) {
            this.anchors = anchors;
            this.at = at;
        }

        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method,
                                        XMLCryptoContext context) throws KeySelectorException {
            if (keyInfo == null || purpose != Purpose.VERIFY) {
                throw new KeySelectorException("sin KeyInfo");
            }
            List<X509Certificate> certs = new ArrayList<>();
            for (Object o : keyInfo.getContent()) {
                if (o instanceof X509Data data) {
                    for (Object c : data.getContent()) {
                        if (c instanceof X509Certificate x) {
                            certs.add(x);
                        }
                    }
                }
            }
            if (certs.isEmpty() || certs.size() > 5) {
                throw new KeySelectorException("certificados");
            }
            X509Certificate leaf = certs.getFirst();
            try {
                X509CertSelector target = new X509CertSelector();
                target.setCertificate(leaf);
                PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, target);
                params.setRevocationEnabled(false);
                params.setDate(at);
                params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certs)));
                CertPathBuilder.getInstance("PKIX").build(params);
            } catch (Exception e) {
                throw new KeySelectorException("cadena");
            }
            boolean[] usage = leaf.getKeyUsage();
            if (usage != null && !(usage[0] || usage[1])) {
                throw new KeySelectorException("uso");
            }
            signer = leaf;
            Key key = leaf.getPublicKey();
            return () -> key;
        }
    }

    /** La cédula que va en el SERIALNUMBER del certificado (ej. CPJ-3-101-123456). */
    private static String signerId(X509Certificate cert) throws InvoiceRejected {
        String dn = cert.getSubjectX500Principal().getName(X500Principal.RFC2253, Map.of("2.5.4.5", "SERIALNUMBER"));
        try {
            for (Rdn rdn : new LdapName(dn).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("SERIALNUMBER")) {
                    String id = stripZeros(digits(String.valueOf(rdn.getValue())));
                    if (!id.isEmpty()) {
                        return id;
                    }
                }
            }
        } catch (InvalidNameException e) {
            throw new InvoiceRejected("firmante");
        }
        throw new InvoiceRejected("firmante");
    }

    // --- lectura del XML ------------------------------------------------------------------------

    private static void requireNamespace(Element root, String name) throws InvoiceRejected {
        String ns = root.getNamespaceURI();
        if (ns == null || !ns.startsWith(NS_PREFIX) || !ns.endsWith("/" + name) || ns.length() > 120) {
            throw new InvoiceRejected("esquema");
        }
    }

    /** El único hijo directo con ese nombre (en el mismo esquema). Cero o varios = rechazo. */
    private static Element only(Element parent, String name) throws InvoiceRejected {
        Element e = optional(parent, name);
        if (e == null) {
            throw new InvoiceRejected("falta-" + name);
        }
        return e;
    }

    private static Element optional(Element parent, String name) throws InvoiceRejected {
        String ns = parent.getOwnerDocument().getDocumentElement().getNamespaceURI();
        Element found = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) {
                if (found != null) {
                    throw new InvoiceRejected("repetido-" + name);
                }
                found = e;
            }
        }
        return found;
    }

    private static String text(Element parent, String name) throws InvoiceRejected {
        String value = only(parent, name).getTextContent().strip();
        if (value.isEmpty() || value.length() > 200) {
            throw new InvoiceRejected("campo-" + name);
        }
        return value;
    }

    private static BigDecimal amount(String raw) throws InvoiceRejected {
        if (!raw.matches("[0-9]{1,13}(\\.[0-9]{1,5})?")) {
            throw new InvoiceRejected("monto");
        }
        BigDecimal value = new BigDecimal(raw);
        if (value.signum() <= 0 || value.compareTo(MAX_TOTAL) >= 0) {
            throw new InvoiceRejected("monto");
        }
        return value;
    }

    /** Nombre para mostrar: sin caracteres de control, espacios simples, 100 caracteres. */
    static String clean(String raw) {
        String s = raw.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        return s.length() > 100 ? s.substring(0, 100).strip() : s;
    }

    private static String digits(String s) {
        return s.length() > 40 ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String stripZeros(String s) {
        return s.replaceFirst("^0+", "");
    }
}

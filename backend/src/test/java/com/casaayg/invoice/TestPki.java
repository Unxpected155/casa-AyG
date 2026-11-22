package com.casaayg.invoice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;

/**
 * Autoridades y certificados SOLO de prueba, creados con keytool (el del JDK) en una carpeta
 * temporal en cada ejecución: no hay llaves guardadas en el repo.
 */
final class TestPki {

    record Signer(PrivateKey key, X509Certificate cert) {}

    private static final String PASS = "prueba";
    private final Path dir;

    TestPki() throws Exception {
        dir = Files.createTempDirectory("casa-pki");
    }

    /** Autoridad autofirmada. */
    Signer authority(String alias) throws Exception {
        keytool("-genkeypair", "-keystore", ks(alias), "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=CA " + alias, "-ext", "bc:c", "-startdate", "-30d", "-validity", "3650");
        return load(alias, null);
    }

    /** Certificado emitido por {@code ca} para el SERIALNUMBER dado (ej. CPJ-3101999999). */
    Signer issue(Signer ca, String caAlias, String alias, String serialNumber, String keyUsage) throws Exception {
        keytool("-genkeypair", "-keystore", ks(alias), "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=" + alias + ", SERIALNUMBER=" + serialNumber, "-validity", "3650");
        Path csr = dir.resolve(alias + ".csr");
        Path cer = dir.resolve(alias + ".cer");
        keytool("-certreq", "-keystore", ks(alias), "-alias", alias, "-file", csr.toString());
        keytool("-gencert", "-keystore", ks(caAlias), "-alias", caAlias, "-infile", csr.toString(),
            "-outfile", cer.toString(), "-ext", "ku:c=" + keyUsage, "-startdate", "-10d", "-validity", "3000");
        return load(alias, cer);
    }

    private Signer load(String alias, Path cer) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(ks(alias))) {
            store.load(in, PASS.toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey(alias, PASS.toCharArray());
        X509Certificate cert = cer == null ? (X509Certificate) store.getCertificate(alias)
            : (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(cer)));
        return new Signer(key, cert);
    }

    private String ks(String alias) {
        return dir.resolve(alias + ".p12").toString();
    }

    private void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        Collections.addAll(cmd, args);
        Collections.addAll(cmd, "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS, "-noprompt");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool falló: " + out);
        }
    }

    // --- firma XML (enveloped, del documento completo, como los comprobantes) ---------------

    static byte[] sign(String xml, Signer signer) throws Exception {
        return sign(xml, signer, "");
    }

    /** {@code referenceUri} distinto de "" sirve para probar firmas que no cubren todo. */
    static byte[] sign(String xml, Signer signer, String referenceUri) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        if (!referenceUri.isEmpty()) {
            var target = doc.getElementsByTagNameNS("*", "Clave").item(0);
            ((org.w3c.dom.Element) target).setAttribute("Id", referenceUri.substring(1));
            ((org.w3c.dom.Element) target).setIdAttribute("Id", true);
        }
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        Reference ref = fac.newReference(referenceUri, fac.newDigestMethod(DigestMethod.SHA256, null),
            List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)), null, null);
        SignedInfo si = fac.newSignedInfo(
            fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
            fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(ref));
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        var keyInfo = kif.newKeyInfo(List.of(kif.newX509Data(List.of(signer.cert()))));
        XMLSignature signature = fac.newXMLSignature(si, keyInfo);
        DOMSignContext ctx = new DOMSignContext(signer.key(), doc.getDocumentElement());
        ctx.setDefaultNamespacePrefix("ds");
        signature.sign(ctx);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }
}

package com.casaayg.invoice;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Las autoridades de confianza para las firmas de los comprobantes son certificados PÚBLICOS en
 * {@code src/main/resources/invoice-trust/*.pem} (van en el repo, revisables). La cédula con que
 * firma Hacienda sus respuestas, en {@code casaayg.invoices.hacienda-id}. Sin ellas, el
 * verificador rechaza todo (falla cerrado).
 */
@Configuration
class InvoiceVerifierConfig {

    private static final Logger log = LoggerFactory.getLogger(InvoiceVerifierConfig.class);

    @Bean
    InvoiceVerifier invoiceVerifier(@Value("${casaayg.invoices.hacienda-id:}") String haciendaId) throws IOException {
        List<X509Certificate> anchors = new ArrayList<>();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:invoice-trust/*.pem");
        for (Resource r : files) {
            try (InputStream in = r.getInputStream()) {
                for (var c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    anchors.add((X509Certificate) c);
                }
            } catch (CertificateException e) {
                throw new IllegalStateException("certificado inválido en invoice-trust: " + r.getFilename(), e);
            }
        }
        log.info("Facturas: {} autoridades de confianza; Hacienda {}", anchors.size(),
            haciendaId.isBlank() ? "sin configurar" : "configurada");
        return new InvoiceVerifier(anchors, haciendaId, Clock.systemUTC());
    }
}

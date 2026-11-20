package com.casaayg.invoice;

import java.io.ByteArrayInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parser de XML para input NO confiable (un adjunto de correo). Sin DTD, sin entidades
 * externas, sin XInclude y con tamaño máximo: nada de XXE ni de "billion laughs".
 */
final class SafeXml {

    static final int MAX_BYTES = 1024 * 1024;

    private SafeXml() {}

    static Document parse(byte[] xml) throws InvoiceRejected {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) {
            throw new InvoiceRejected("tamaño");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("entidad externa");
            });
            b.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) {}
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new InvoiceRejected("xml");
        }
    }
}

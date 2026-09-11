package com.mapleretail.silkroute.esb.xslt;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.springframework.core.io.ClassPathResource;

/**
 * Cached-{@link Templates} XSLT runner for the mediation legs (JDK built-in
 * Transformer engine, XSLT 1.0). One Templates object per stylesheet; a fresh
 * Transformer per invocation (Transformer is not thread-safe, Templates is).
 */
public final class XsltTransformer {

    private final ConcurrentHashMap<String, Templates> cache = new ConcurrentHashMap<>();

    /**
     * Runs a classpath stylesheet against the input XML string and returns the
     * result document as a string. Parameters (e.g. storeId, currency,
     * lineIndex) are set before the transform.
     */
    public String transform(String classpathStylesheet, String inputXml, Map<String, Object> parameters) {
        try {
            Templates templates = cache.computeIfAbsent(classpathStylesheet, XsltTransformer::compile);
            Transformer transformer = templates.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            if (parameters != null) {
                parameters.forEach(transformer::setParameter);
            }
            StringWriter out = new StringWriter();
            transformer.transform(source(inputXml), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("XSLT leg failed (" + classpathStylesheet + "): " + e.getMessage(), e);
        }
    }

    public String transform(String classpathStylesheet, String inputXml) {
        return transform(classpathStylesheet, inputXml, null);
    }

    private static Templates compile(String classpathStylesheet) {
        try {
            Source xsl = new StreamSource(
                    new ClassPathResource(classpathStylesheet).getInputStream());
            TransformerFactory factory = TransformerFactory.newInstance();
            // The legs are self-contained; external entity resolution is off (XXE hardening).
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            return factory.newTemplates(xsl);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compile stylesheet " + classpathStylesheet, e);
        }
    }

    private static StreamSource source(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}

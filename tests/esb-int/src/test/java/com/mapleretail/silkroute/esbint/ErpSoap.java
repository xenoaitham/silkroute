package com.mapleretail.silkroute.esbint;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Raw SOAP 1.2 + WS-Security UsernameToken client for the legacy ERP — the
 * SAME wire shape the Phase 1 Karate contract suite freezes (tests/contract).
 * Used ONLY for independent ground truth (getStock / priceForSku): the proof
 * that saga compensation really released a hold on the ERP, and that business
 * faults leaked no stock. Calls go DIRECT to 127.0.0.1:18080 (not through the
 * proxy) so verification reads stay reliable while toxics are active.
 *
 * Parse is namespace-aware and keyed by LOCAL NAME: CXF picks its own
 * throwaway prefixes per response (soap:, ns2:), so contract assertions must
 * never depend on them.
 */
final class ErpSoap {

    static final String WS_USER =
            firstNonBlank(System.getenv("ERP_WSS_USERNAME"), "esb-client");
    static final String WS_PASS =
            firstNonBlank(System.getenv("ERP_WSS_PASSWORD"), "erp-wss-pass-2026");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ErpSoap() {
    }

    /** Fresh UsernameToken per request — the ERP keeps a nonce replay cache. */
    private static String securityHeader() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        return "<wsse:Security xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\""
                + " soap:mustUnderstand=\"1\"><wsse:UsernameToken>"
                + "<wsse:Username>" + WS_USER + "</wsse:Username>"
                + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText\">"
                + WS_PASS + "</wsse:Password>"
                + "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + nonce + "</wsse:Nonce>"
                + "<wsu:Created xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                + UTC.format(Instant.now()) + "</wsu:Created>"
                + "</wsse:UsernameToken></wsse:Security>";
    }

    private static String envelope(String bodyXml) {
        return "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:minv=\"urn:maple:erp:inventory:v1\""
                + " xmlns:mprc=\"urn:maple:erp:pricing:v1\">"
                + "<soap:Header>" + securityHeader() + "</soap:Header>"
                + "<soap:Body>" + bodyXml + "</soap:Body></soap:Envelope>";
    }

    private static Map<String, Object> post(String path, String action, String bodyXml) {
        String xml = envelope(bodyXml);
        HttpRequest req = HttpRequest.newBuilder(URI.create(Wire.ERP_BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("SOAP POST " + path + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SOAP POST " + path + " interrupted", e);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("SOAP POST " + path + " -> HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }
        return parse(resp.body());
    }

    /** getStock -> {storeId, skuId, region, onHandQuantity, reservedQuantity, availableQuantity}. */
    static Map<String, Object> getStock(String storeId, String skuId) {
        Map<String, Object> parsed = post("/ws/inventory/v1", "urn:maple:erp:inventory:v1:getStock",
                "<minv:getStockRequest><minv:storeId>" + storeId + "</minv:storeId>"
                        + "<minv:skuId>" + skuId + "</minv:skuId></minv:getStockRequest>");
        return childMap(soapBodyElement(parsed, "getStockResponse"));
    }

    static int availableQuantity(String storeId, String skuId) {
        Object v = getStock(storeId, skuId).get("availableQuantity");
        if (v == null) {
            throw new IllegalStateException("getStock " + storeId + "/" + skuId
                    + " returned no availableQuantity");
        }
        return Integer.parseInt(String.valueOf(v));
    }

    /** priceForSku -> unit price amountMinor in the requested currency (exact integer). */
    static int unitPriceMinor(String skuId, String currency) {
        Map<String, Object> parsed = post("/ws/pricing/v1", "urn:maple:erp:pricing:v1:priceForSku",
                "<mprc:priceForSkuRequest><mprc:skuId>" + skuId + "</mprc:skuId>"
                        + "<mprc:currency>" + currency + "</mprc:currency></mprc:priceForSkuRequest>");
        Map<String, Object> res = childMap(soapBodyElement(parsed, "priceForSkuResponse"));
        Object unitPrice = res.get("unitPrice");
        Object amount = unitPrice instanceof Map<?, ?> ? asMap(unitPrice).get("amountMinor") : null;
        if (amount == null) {
            throw new IllegalStateException("priceForSku " + skuId + "/" + currency
                    + " returned no unitPrice.amountMinor: " + res);
        }
        return Integer.parseInt(String.valueOf(amount));
    }

    /** Navigate the local-name tree: Envelope -> Body -> <operation>Response. */
    private static Object soapBodyElement(Map<String, Object> parsed, String element) {
        Object envelope = parsed.get("Envelope");
        Object body = envelope instanceof Map<?, ?> ? asMap(envelope).get("Body") : null;
        Object elementValue = body instanceof Map<?, ?> ? asMap(body).get(element) : null;
        if (elementValue == null) {
            throw new IllegalStateException("SOAP body element '" + element + "' not found in: " + parsed);
        }
        return elementValue;
    }

    private static Map<String, Object> childMap(Object o) {
        if (!(o instanceof Map<?, ?>)) {
            throw new IllegalStateException("expected a nested SOAP element, got: " + o);
        }
        return asMap(o);
    }

    // ------------------------------------------------- namespace-free parsing

    /** Namespace-aware DOM -> Map/List/String keyed by LOCAL NAME (prefixes are throwaway). */
    static Map<String, Object> parse(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(local(root), toJava(root));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse SOAP XML: " + xml, e);
        }
    }

    private static Object toJava(Element el) {
        Map<String, Object> children = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String name = local((Element) n);
                Object value = toJava((Element) n);
                Object existing = children.get(name);
                if (existing == null) {
                    children.put(name, value);
                } else if (existing instanceof List<?> list) {
                    List<Object> mutable = new ArrayList<>(list);
                    mutable.add(value);
                    children.put(name, mutable);
                } else {
                    List<Object> list = new ArrayList<>();
                    list.add(existing);
                    list.add(value);
                    children.put(name, list);
                }
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(n.getNodeValue());
            }
        }
        return children.isEmpty() ? text.toString().trim() : children;
    }

    private static String local(Element el) {
        return el.getLocalName() != null ? el.getLocalName() : el.getNodeName();
    }

    private static Map<String, Object> asMap(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a.trim() : b;
    }
}

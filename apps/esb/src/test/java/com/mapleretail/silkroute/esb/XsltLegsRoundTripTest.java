package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.xml.sax.SAXException;

import com.mapleretail.silkroute.esb.canonical.CanonicalXmlCodec;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalAudit;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalLine;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalMoney;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;
import com.mapleretail.silkroute.esb.xslt.XsltTransformer;

/**
 * The four XSLT mediation legs, tested as real stylesheets (NOT pass-through):
 * canonical → frozen ERP request → (simulated frozen ERP response) → canonical,
 * with the ERP-side documents validated against the FROZEN XSDs (read-only,
 * freeze tag contract-freeze-erp-v1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XsltLegsRoundTripTest {

    private static final String FROZEN_XSD_DIR = "../legacy-erp/src/main/resources/xsd/";

    private CanonicalXmlCodec canonical;
    private XsltTransformer xslt;

    @BeforeAll
    void setUp() {
        canonical = new CanonicalXmlCodec();
        xslt = new XsltTransformer();
    }

    private CanonicalOrder sampleOrder() {
        CanonicalOrder order = new CanonicalOrder();
        order.setExternalOrderRef("WEB-20260911-0001");
        order.setSourceSystem("WEB_STORE_CA");
        order.setStoreId("ST-CA-01");
        order.setCustomerRef("cust-macdonald-42");
        order.setChannel("WEB_STORE");
        order.setLines(List.of(new CanonicalLine("SKU-0001", 2), new CanonicalLine("SKU-0002", 1)));
        CanonicalAudit audit = new CanonicalAudit();
        audit.setSourceSystem("WEB_STORE_CA");
        audit.setReceivedAt("2026-09-11T10:00:00.000Z");
        audit.setCorrelationId("corr-e2e-0001");
        order.setAudit(audit);
        return order;
    }

    /** Validates ERP-side XML against the FROZEN schema (namespace-aware, import-resolving). */
    private void assertValidAgainstFrozenXsd(String xsdFile, String xml) throws SAXException, java.io.IOException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = factory.newSchema(new java.io.File(FROZEN_XSD_DIR + xsdFile));
        Validator validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    // ------------------------------------------------------ orders leg

    @Test
    void canonicalToSubmitRequestIsValidAgainstFrozenSchemaAndPreservesAudit() throws Exception {
        String erpRequestXml = xslt.transform("xslt/canonical-to-submit-request.xsl", canonical.marshal(sampleOrder()));

        assertValidAgainstFrozenXsd("orders-v1.xsd", erpRequestXml);

        assertTrue(erpRequestXml.contains("submitOrderRequest"));
        assertTrue(erpRequestXml.contains("urn:maple:erp:orders:v1"), "wrapped element ns");
        assertTrue(erpRequestXml.contains("<mcom:sourceSystem>WEB_STORE_CA</mcom:sourceSystem>")
                || erpRequestXml.contains("sourceSystem"), "audit block built from canonical audit");
        assertTrue(erpRequestXml.contains("2026-09-11T10:00:00.000Z"));
        assertTrue(erpRequestXml.contains("corr-e2e-0001"));
        // typed contract bean parse: the CXF port can consume exactly this document
        ErpXmlCodecBridge codec = new ErpXmlCodecBridge();
        com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest typed =
                codec.parseSubmitRequest(erpRequestXml);
        assertEquals("WEB-20260911-0001", typed.getExternalOrderRef());
        assertEquals("ST-CA-01", typed.getStoreId());
        assertEquals("cust-macdonald-42", typed.getCustomerRef());
        assertEquals(2, typed.getLines().size());
        assertEquals("SKU-0002", typed.getLines().get(1).getSkuId());
        assertEquals(1, typed.getLines().get(1).getQuantity());
        assertEquals("WEB_STORE", typed.getOrderChannel().value());
        assertEquals("corr-e2e-0001", typed.getAudit().getCorrelationId());
    }

    @Test
    void submitResponseToCanonicalRoundTripPreservesIdentityAndMoney() throws Exception {
        String erpResponseXml = """
                <mord:submitOrderResponse xmlns:mord="urn:maple:erp:orders:v1" xmlns:mcom="urn:maple:erp:common:v1">
                  <mord:orderId>ORD-2026-000777</mord:orderId>
                  <mord:externalOrderRef>WEB-20260911-0001</mord:externalOrderRef>
                  <mord:status>SUBMITTED</mord:status>
                  <mord:submittedAt>2026-09-11T10:00:01.000Z</mord:submittedAt>
                  <mord:totalAmount>
                    <mcom:amountMinor>12999</mcom:amountMinor>
                    <mcom:currency>CAD</mcom:currency>
                  </mord:totalAmount>
                  <mord:lineCount>2</mord:lineCount>
                </mord:submitOrderResponse>
                """;
        String canonicalXml = xslt.transform("xslt/submit-response-to-canonical.xsl", erpResponseXml,
                Map.of("storeId", "ST-CA-01", "channel", "WEB_STORE"));

        assertTrue(canonicalXml.contains("urn:maple:canonical:order:v1"));
        CanonicalConfirmedOrder confirmed = canonical.unmarshalConfirmedOrder(canonicalXml);
        assertEquals("ORD-2026-000777", confirmed.getOrderId());
        assertEquals("SUBMITTED", confirmed.getStatus());
        assertEquals("WEB-20260911-0001", confirmed.getExternalOrderRef());
        assertEquals("ST-CA-01", confirmed.getStoreId(), "storeId supplied as XSLT param from request context");
        assertEquals("WEB_STORE", confirmed.getChannel());
        assertEquals(12999L, confirmed.getTotalAmount().getAmountMinor(), "integer minor units (C5)");
        assertEquals("CAD", confirmed.getTotalAmount().getCurrency());
        assertEquals(2, confirmed.getLineCount());
    }

    // ----------------------------------------------------- pricing leg

    @Test
    void canonicalToPriceRequestTargetsRequestedLineAndRegionCurrency() throws Exception {
        String requestXml = xslt.transform("xslt/canonical-to-price-request.xsl", canonical.marshal(sampleOrder()),
                Map.of("currency", "CAD", "lineIndex", 2));

        assertValidAgainstFrozenXsd("pricing-v1.xsd", requestXml);

        ErpXmlCodecBridge codec = new ErpXmlCodecBridge();
        com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuRequest typed =
                codec.parsePriceRequest(requestXml);
        assertEquals("SKU-0002", typed.getSkuId(), "1-based lineIndex picks the 2nd line");
        assertEquals("CAD", typed.getCurrency().value());
    }

    @Test
    void priceResponseToCanonicalCarriesIntegerMinorUnits() throws Exception {
        String erpResponseXml = """
                <mprc:priceForSkuResponse xmlns:mprc="urn:maple:erp:pricing:v1" xmlns:mcom="urn:maple:erp:common:v1">
                  <mprc:skuId>SKU-0002</mprc:skuId>
                  <mprc:unitPrice>
                    <mcom:amountMinor>4599</mcom:amountMinor>
                    <mcom:currency>CAD</mcom:currency>
                  </mprc:unitPrice>
                  <mprc:priceListCode>STD-2026</mprc:priceListCode>
                  <mprc:effectiveFrom>2026-01-01</mprc:effectiveFrom>
                  <mprc:validUntil>2026-12-31</mprc:validUntil>
                  <mprc:discountEligible>false</mprc:discountEligible>
                </mprc:priceForSkuResponse>
                """;
        String unitPriceXml = xslt.transform("xslt/price-response-to-canonical.xsl", erpResponseXml);
        assertTrue(unitPriceXml.contains("urn:maple:canonical:order:v1"));
        CanonicalUnitPrice price = canonical.unmarshalUnitPrice(unitPriceXml);
        assertEquals("SKU-0002", price.getSkuId());
        assertEquals(4599L, price.getAmountMinor());
        assertEquals("CAD", price.getCurrency());
    }

    @Test
    void customerRefIsOmittedFromErpRequestWhenAbsent() throws Exception {
        CanonicalOrder order = sampleOrder();
        order.setCustomerRef(null);
        String erpRequestXml = xslt.transform("xslt/canonical-to-submit-request.xsl", canonical.marshal(order));
        assertValidAgainstFrozenXsd("orders-v1.xsd", erpRequestXml);
        assertTrue(!erpRequestXml.contains("customerRef"));
    }

    /** Bridges the main-code codec for typed assertions (same class the runtime uses). */
    static final class ErpXmlCodecBridge {
        private final com.mapleretail.silkroute.esb.erp.ErpXmlCodec delegate = new com.mapleretail.silkroute.esb.erp.ErpXmlCodec();

        com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest parseSubmitRequest(String xml) {
            return delegate.parseSubmitRequest(xml);
        }

        com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuRequest parsePriceRequest(String xml) {
            return delegate.parsePriceRequest(xml);
        }
    }
}

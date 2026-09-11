package com.mapleretail.silkroute.esb.erp;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import com.mapleretail.silkroute.legacyerp.contract.common.LegacyAuditType;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveRequest;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderResponse;
import com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuRequest;
import com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuResponse;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

/**
 * JAXB codec for the FROZEN ERP contract beans (generated read-only from the
 * frozen WSDLs — constraint C6). Used to hand XSLT output to the CXF typed ports
 * and to feed typed responses into the XSLT response legs.
 */
public final class ErpXmlCodec {

    private final JAXBContext context;
    private final DatatypeFactory datatypeFactory;

    public ErpXmlCodec() {
        try {
            this.context = JAXBContext.newInstance(
                    com.mapleretail.silkroute.legacyerp.contract.orders.ObjectFactory.class,
                    com.mapleretail.silkroute.legacyerp.contract.inventory.ObjectFactory.class,
                    com.mapleretail.silkroute.legacyerp.contract.pricing.ObjectFactory.class,
                    com.mapleretail.silkroute.legacyerp.contract.common.ObjectFactory.class);
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build ERP contract JAXB context", e);
        }
    }

    /** Marshal a typed ERP request/response bean (root element annotated) to XML text. */
    public String marshal(Object root) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            marshaller.marshal(root, out);
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot marshal ERP contract element: " + e.getMessage(), e);
        }
    }

    /**
     * Unmarshal an ERP request/response document (e.g. the output of an XSLT
     * leg) to its typed bean. The generated beans carry @XmlRootElement, so a
     * plain class-targeted unmarshal works (no JAXBElement indirection).
     */
    public <T> T unmarshal(String xml, Class<T> rootType) {
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Object parsed = unmarshaller.unmarshal(new javax.xml.transform.stream.StreamSource(new StringReader(xml)));
            return rootType.cast(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot unmarshal ERP document to " + rootType.getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    public String toXml(SubmitOrderResponse response) {
        return marshal(response);
    }

    public String toXml(PriceForSkuResponse response) {
        return marshal(response);
    }

    public SubmitOrderRequest parseSubmitRequest(String xml) {
        return unmarshal(xml, SubmitOrderRequest.class);
    }

    public PriceForSkuRequest parsePriceRequest(String xml) {
        return unmarshal(xml, PriceForSkuRequest.class);
    }

    public static com.mapleretail.silkroute.legacyerp.contract.orders.ObjectFactory ordersFactory() {
        return new com.mapleretail.silkroute.legacyerp.contract.orders.ObjectFactory();
    }

    public static com.mapleretail.silkroute.legacyerp.contract.inventory.ObjectFactory inventoryFactory() {
        return new com.mapleretail.silkroute.legacyerp.contract.inventory.ObjectFactory();
    }

    public static com.mapleretail.silkroute.legacyerp.contract.pricing.ObjectFactory pricingFactory() {
        return new com.mapleretail.silkroute.legacyerp.contract.pricing.ObjectFactory();
    }

    public static com.mapleretail.silkroute.legacyerp.contract.common.ObjectFactory commonFactory() {
        return new com.mapleretail.silkroute.legacyerp.contract.common.ObjectFactory();
    }

    /** Build the LegacyAuditType block (correlation ids preserved end-to-end). */
    public LegacyAuditType audit(String sourceSystem, String receivedAt, String correlationId) {
        LegacyAuditType audit = new LegacyAuditType();
        audit.setSourceSystem(sourceSystem);
        audit.setReceivedAt(xmlDateTime(receivedAt));
        audit.setCorrelationId(correlationId);
        return audit;
    }

    public XMLGregorianCalendar xmlDateTime(String isoDateTime) {
        try {
            return datatypeFactory.newXMLGregorianCalendar(isoDateTime);
        } catch (IllegalArgumentException e) {
            // Schema-validated input should always parse; keep the saga alive with UTC now if not.
            java.util.GregorianCalendar fallback = new java.util.GregorianCalendar(
                    java.util.TimeZone.getTimeZone("UTC"));
            return datatypeFactory.newXMLGregorianCalendar(fallback);
        }
    }

    public ReserveRequest reserveRequest(String reservationRef, String storeId, String skuId, int quantity,
            LegacyAuditType audit) {
        ReserveRequest request = new ReserveRequest();
        request.setReservationRef(reservationRef);
        request.setStoreId(storeId);
        request.setSkuId(skuId);
        request.setQuantity(quantity);
        request.setAudit(audit);
        return request;
    }

    public ReleaseRequest releaseRequest(String reservationId, LegacyAuditType audit) {
        ReleaseRequest request = new ReleaseRequest();
        request.setReservationId(reservationId);
        // quantity omitted = release the full outstanding reservation (frozen contract).
        request.setAudit(audit);
        return request;
    }

    /** DOM Document factory for DOMResult targets (not currently used on the hot path). */
    public static org.w3c.dom.Document newDocument() {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(true);
            return dbf.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create DOM document", e);
        }
    }

    public static String documentToString(org.w3c.dom.Node node) {
        try {
            StringWriter out = new StringWriter();
            javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            t.transform(new DOMSource(node), new javax.xml.transform.stream.StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize DOM node", e);
        }
    }

    public static org.w3c.dom.Document toDom(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(true);
            javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            org.w3c.dom.Document document = dbf.newDocumentBuilder().newDocument();
            t.transform(new javax.xml.transform.stream.StreamSource(new StringReader(xml)),
                    new DOMResult(document));
            return document;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse XML into DOM", e);
        }
    }
}

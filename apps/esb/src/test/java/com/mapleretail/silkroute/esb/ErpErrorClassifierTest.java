package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.namespace.QName;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.esb.erp.ErpErrorClassifier;
import com.mapleretail.silkroute.legacyerp.contract.inventory.OutOfStockFault;
import com.mapleretail.silkroute.legacyerp.contract.orders.InvalidOrderFault;
import com.mapleretail.silkroute.legacyerp.contract.pricing.UnknownSkuFault;

import jakarta.xml.soap.Detail;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPFactory;
import jakarta.xml.soap.SOAPFault;
import jakarta.xml.ws.soap.SOAPFaultException;

/**
 * ADR-0003 fault mapping (Phase 2 acceptance item): ERP business faults arrive
 * with wire fault code soap:Receiver (CXF default the frozen estate keeps), yet
 * they MUST map to SENDER/BUSINESS class. The wire code is ignored; the typed
 * fault exception / Detail element decides. Network faults map to RECEIVER/infra.
 */
class ErpErrorClassifierTest {

    private final DatatypeFactory dtf = DatatypeFactoryHolder.INSTANCE;

    static final class DatatypeFactoryHolder {
        static final DatatypeFactory INSTANCE = newFactory();

        private static DatatypeFactory newFactory() {
            try {
                return DatatypeFactory.newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // NOTE: the four generated EXCEPTION classes live in maple.erp.<domain>.v1;
    // this test uses the raw SOAP-fault path for two of them and the typed
    // exception path for the others.

    private maple.erp.orders.v1.InvalidOrderFault typedInvalidOrder() {
        InvalidOrderFault detail = new InvalidOrderFault();
        detail.setErrorCode("ORD-DUP-REF");
        detail.setErrorMessage("duplicate externalOrderRef for source system 'WEB_CA' (orderId ORD-2026-000042)");
        detail.setSourceSubsystem("ORDERS");
        detail.setOccurredAt(dtf.newXMLGregorianCalendar(2026, 9, 11, 8, 0, 0, 0, 0));
        return new maple.erp.orders.v1.InvalidOrderFault("duplicate externalOrderRef", detail);
    }

    private maple.erp.pricing.v1.UnknownSkuFault typedUnknownSku() {
        UnknownSkuFault detail = new UnknownSkuFault();
        detail.setErrorCode("PRC-SKU-UNKNOWN");
        detail.setErrorMessage("unknown SKU: SKU-9999");
        detail.setSourceSubsystem("PRICING");
        detail.setOccurredAt(dtf.newXMLGregorianCalendar(2026, 9, 11, 8, 0, 0, 0, 0));
        return new maple.erp.pricing.v1.UnknownSkuFault("unknown SKU: SKU-9999", detail);
    }

    private OutOfStockFault outOfStockDetail() {
        OutOfStockFault detail = new OutOfStockFault();
        detail.setErrorCode("INV-OUT-OF-STOCK");
        detail.setErrorMessage("insufficient stock at ST-CA-01 for SKU-0002");
        detail.setSourceSubsystem("INVENTORY");
        detail.setOccurredAt(dtf.newXMLGregorianCalendar(2026, 9, 11, 8, 0, 0, 0, 0));
        detail.setStoreId("ST-CA-01");
        detail.setSkuId("SKU-0002");
        detail.setRequestedQuantity(10);
        detail.setAvailableQuantity(3);
        return detail;
    }

    /** Builds a raw SOAP 1.2 fault: Code/Value=Receiver (wire case!) + typed Detail element. */
    private SOAPFaultException receiverCodedFaultWithDetail(String detailLocalName, String ns, OutOfStockFault payload)
            throws Exception {
        SOAPFactory factory = SOAPFactory.newInstance();
        SOAPFault fault = factory.createFault();
        fault.setFaultCode(SOAPConstants.SOAP_SENDER_FAULT); // payload code is irrelevant to classification
        ((SOAPElement) fault).addChildElement(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Code"));
        // set the 1.2 Code/Value explicitly to soap:Receiver — the CXF default on this estate
        fault.setFaultCode(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Receiver"));
        Detail detail = fault.addDetail();
        SOAPElement element = detail.addDetailEntry(new QName(ns, detailLocalName));
        element.addChildElement(new QName("urn:maple:erp:common:v1", "errorCode")).addTextNode(payload.getErrorCode());
        element.addChildElement(new QName("urn:maple:erp:common:v1", "errorMessage"))
                .addTextNode(payload.getErrorMessage());
        element.addChildElement(new QName("urn:maple:erp:common:v1", "sourceSubsystem"))
                .addTextNode(payload.getSourceSubsystem());
        element.addChildElement(new QName("urn:maple:erp:common:v1", "occurredAt"))
                .addTextNode(payload.getOccurredAt().toXMLFormat());
        element.addChildElement(new QName("urn:maple:erp:inventory:v1", "skuId")).addTextNode(payload.getSkuId());
        return new SOAPFaultException(fault);
    }

    @Test
    void typedInvalidOrderFaultMapsToBusinessEvenThoughWireCodeIsReceiver() {
        maple.erp.orders.v1.InvalidOrderFault fault = typedInvalidOrder();
        assertTrue(ErpErrorClassifier.isBusinessFault(fault));
        assertFalse(ErpErrorClassifier.isInfraFailure(fault));
        var business = ErpErrorClassifier.toBusinessException(fault);
        assertEquals("ORD-DUP-REF", business.getErrorCode());
        assertEquals("ORDERS", business.getSourceSubsystem());
        assertTrue(business.getMessage().contains("duplicate externalOrderRef"));
    }

    @Test
    void typedUnknownSkuFaultMapsToBusiness() {
        assertTrue(ErpErrorClassifier.isBusinessFault(typedUnknownSku()));
        assertEquals("PRC-SKU-UNKNOWN", ErpErrorClassifier.toBusinessException(typedUnknownSku()).getErrorCode());
    }

    @Test
    void receiverWireCodeWithTypedDetailElementMapsToBusinessSenderClass() throws Exception {
        // THE ADR-0003 case: wire Code/Value = soap:Receiver, Detail = OutOfStockFault.
        SOAPFaultException fault = receiverCodedFaultWithDetail("OutOfStockFault", "urn:maple:erp:inventory:v1",
                outOfStockDetail());
        assertEquals("Receiver", fault.getFault().getFaultCodeAsQName().getLocalPart(), "precondition: wire code");
        assertTrue(ErpErrorClassifier.isBusinessFault(fault),
                "Receiver wire code must NOT downgrade a typed business fault to infra");
        var business = ErpErrorClassifier.toBusinessException(fault);
        assertEquals("INV-OUT-OF-STOCK", business.getErrorCode());
        assertEquals("INVENTORY", business.getSourceSubsystem());
    }

    @Test
    void receiverFaultWithoutErpDetailIsInfra() throws Exception {
        SOAPFactory factory = SOAPFactory.newInstance();
        SOAPFault fault = factory.createFault();
        fault.setFaultCode(new QName(SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE, "Receiver"));
        Detail detail = fault.addDetail();
        detail.addDetailEntry(new QName("urn:maple:erp:inventory:v1", "SomethingElseUnexpected"));
        SOAPFaultException e = new SOAPFaultException(fault);
        assertFalse(ErpErrorClassifier.isBusinessFault(e));
        assertTrue(ErpErrorClassifier.isInfraFailure(e));
    }

    @Test
    void timeoutsAndConnectFailuresAreInfra() {
        var timeout = new jakarta.xml.ws.WebServiceException(
                new java.net.SocketTimeoutException("Read timed out after 2000 ms"));
        var refused = new jakarta.xml.ws.WebServiceException(
                new java.net.ConnectException("Connection refused: /127.0.0.1:18180"));
        assertTrue(ErpErrorClassifier.isInfraFailure(timeout));
        assertTrue(ErpErrorClassifier.isInfraFailure(refused));
        assertTrue(ErpErrorClassifier.isTransportFailure(timeout));
        assertTrue(ErpErrorClassifier.isTransportFailure(refused));
        assertTrue(ErpErrorClassifier.isTransportFailure(new RuntimeException(
                new jakarta.xml.ws.WebServiceException(new java.net.SocketTimeoutException("deep")))));
        assertFalse(ErpErrorClassifier.isBusinessFault(timeout));
    }

    @Test
    void unknownReservationFaultMapsToBusiness() {
        com.mapleretail.silkroute.legacyerp.contract.inventory.UnknownReservationFault detail = new com.mapleretail.silkroute.legacyerp.contract.inventory.UnknownReservationFault();
        detail.setErrorCode("INV-UNKNOWN-RESERVATION");
        detail.setErrorMessage("unknown reservationId: RSV-42");
        detail.setSourceSubsystem("INVENTORY");
        detail.setOccurredAt(dtf.newXMLGregorianCalendar(2026, 9, 11, 8, 0, 0, 0, 0));
        detail.setReservationId("RSV-42");
        var fault = new maple.erp.inventory.v1.UnknownReservationFault("unknown reservationId: RSV-42", detail);
        assertTrue(ErpErrorClassifier.isBusinessFault(fault));
        assertEquals("INV-UNKNOWN-RESERVATION", ErpErrorClassifier.toBusinessException(fault).getErrorCode());
    }
}

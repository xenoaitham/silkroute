package com.mapleretail.silkroute.esb.erp;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Set;

import com.mapleretail.silkroute.legacyerp.contract.inventory.OutOfStockFault;
import com.mapleretail.silkroute.legacyerp.contract.inventory.UnknownReservationFault;
import com.mapleretail.silkroute.legacyerp.contract.orders.InvalidOrderFault;
import com.mapleretail.silkroute.legacyerp.contract.pricing.UnknownSkuFault;

import jakarta.xml.soap.Detail;
import jakarta.xml.soap.DetailEntry;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPFault;
import jakarta.xml.ws.soap.SOAPFaultException;

/**
 * ADR-0003 fault mapping (an explicit Phase 2 acceptance item): the frozen ERP
 * emits its business faults with wire fault code soap:Receiver (a CXF default
 * the estate will not change), so the WIRE CODE MUST BE IGNORED. Classification
 * keys on the fault SHAPE instead:
 *
 * <ul>
 *   <li>a typed fault exception generated for the frozen WSDL fault declarations
 *       → BUSINESS (Sender class), even though the wire code says Receiver;</li>
 *   <li>a raw SOAP fault whose Detail carries one of the frozen fault detail
 *       elements (InvalidOrderFault | OutOfStockFault | UnknownSkuFault |
 *       UnknownReservationFault) → BUSINESS (Sender class) — this is the
 *       "Receiver wire code" case;</li>
 *   <li>everything else (connect refused, timeout, transport/security failures)
 *       → INFRA (Receiver class).</li>
 * </ul>
 *
 * Namespace prefixes are throwaway (ADR-0003): detail elements are matched by
 * LOCAL name + the errorCode field.
 */
public final class ErpErrorClassifier {

    /** Local names of the frozen ErpFaultType detail elements. */
    public static final Set<String> ERP_FAULT_DETAIL_LOCAL_NAMES = Set.of(
            "InvalidOrderFault", "OutOfStockFault", "UnknownSkuFault", "UnknownReservationFault");

    private ErpErrorClassifier() {
    }

    public static boolean isBusinessFault(Throwable t) {
        // Generated EXCEPTION classes live in the default namespace packages
        // (maple.erp.<domain>.v1); the fault BEANS (contract.*) come via getFaultInfo().
        return t instanceof maple.erp.orders.v1.InvalidOrderFault
                || t instanceof maple.erp.inventory.v1.OutOfStockFault
                || t instanceof maple.erp.inventory.v1.UnknownReservationFault
                || t instanceof maple.erp.pricing.v1.UnknownSkuFault
                || (t instanceof SOAPFaultException soapFaultException && hasErpFaultDetail(soapFaultException.getFault()));
    }

    public static boolean isInfraFailure(Throwable t) {
        return !isBusinessFault(t);
    }

    /** Walks the cause chain looking for transport-level infra failures. */
    public static boolean isTransportFailure(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static ErpBusinessException toBusinessException(Throwable t) {
        if (t instanceof maple.erp.orders.v1.InvalidOrderFault fault && fault.getFaultInfo() != null) {
            InvalidOrderFault detail = fault.getFaultInfo();
            return new ErpBusinessException(detail.getErrorCode(),
                    firstNonBlank(detail.getErrorMessage(), fault.getMessage()),
                    detail.getSourceSubsystem(), text(detail.getOccurredAt()));
        }
        if (t instanceof maple.erp.inventory.v1.OutOfStockFault fault && fault.getFaultInfo() != null) {
            OutOfStockFault detail = fault.getFaultInfo();
            return new ErpBusinessException(detail.getErrorCode(),
                    firstNonBlank(detail.getErrorMessage(), fault.getMessage()),
                    detail.getSourceSubsystem(), text(detail.getOccurredAt()));
        }
        if (t instanceof maple.erp.inventory.v1.UnknownReservationFault fault && fault.getFaultInfo() != null) {
            UnknownReservationFault detail = fault.getFaultInfo();
            return new ErpBusinessException(detail.getErrorCode(),
                    firstNonBlank(detail.getErrorMessage(), fault.getMessage()),
                    detail.getSourceSubsystem(), text(detail.getOccurredAt()));
        }
        if (t instanceof maple.erp.pricing.v1.UnknownSkuFault fault && fault.getFaultInfo() != null) {
            UnknownSkuFault detail = fault.getFaultInfo();
            return new ErpBusinessException(detail.getErrorCode(),
                    firstNonBlank(detail.getErrorMessage(), fault.getMessage()),
                    detail.getSourceSubsystem(), text(detail.getOccurredAt()));
        }
        if (t instanceof SOAPFaultException soapFaultException) {
            ErpBusinessException parsed = fromFaultDetail(soapFaultException.getFault());
            if (parsed != null) {
                return parsed;
            }
        }
        throw new IllegalArgumentException("Not an ERP business fault: " + t, t);
    }

    /**
     * Parses errorCode/errorMessage/sourceSubsystem/occurredAt out of a raw SOAP
     * fault Detail element (namespace-agnostic: CXF prefixes are throwaway).
     * Returns null when no frozen ERP fault detail element is present.
     */
    public static ErpBusinessException fromFaultDetail(SOAPFault fault) {
        if (fault == null) {
            return null;
        }
        Detail detail = fault.getDetail();
        if (detail == null) {
            return null;
        }
        Iterator<?> entries = detail.getDetailEntries();
        while (entries != null && entries.hasNext()) {
            Object entry = entries.next();
            if (!(entry instanceof DetailEntry detailEntry)) {
                continue;
            }
            String localName = detailEntry.getLocalName();
            if (!ERP_FAULT_DETAIL_LOCAL_NAMES.contains(localName)) {
                continue;
            }
            String errorCode = null;
            String errorMessage = null;
            String sourceSubsystem = null;
            String occurredAt = null;
            Iterator<?> fields = detailEntry.getChildElements();
            while (fields.hasNext()) {
                Object fieldObj = fields.next();
                if (!(fieldObj instanceof SOAPElement field)) {
                    continue;
                }
                switch (field.getLocalName()) {
                    case "errorCode" -> errorCode = field.getValue();
                    case "errorMessage" -> errorMessage = field.getValue();
                    case "sourceSubsystem" -> sourceSubsystem = field.getValue();
                    case "occurredAt" -> occurredAt = field.getValue();
                    default -> {
                    }
                }
            }
            if (errorCode != null) {
                return new ErpBusinessException(errorCode,
                        errorMessage != null ? errorMessage : localName, sourceSubsystem, occurredAt);
            }
        }
        return null;
    }

    private static boolean hasErpFaultDetail(SOAPFault fault) {
        return fromFaultDetail(fault) != null;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

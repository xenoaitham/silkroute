package com.mapleretail.silkroute.legacyerp.fault;

import com.mapleretail.silkroute.legacyerp.contract.common.ErpFaultType;
import com.mapleretail.silkroute.legacyerp.contract.inventory.OutOfStockFault;
import com.mapleretail.silkroute.legacyerp.contract.inventory.UnknownReservationFault;
import com.mapleretail.silkroute.legacyerp.contract.orders.InvalidOrderFault;
import com.mapleretail.silkroute.legacyerp.contract.pricing.UnknownSkuFault;
import com.mapleretail.silkroute.legacyerp.time.XmlDateTimes;

/**
 * Factory for the frozen contract's typed faults. Every fault carries the common
 * ErpFaultType fields (errorCode, errorMessage, sourceSubsystem, occurredAt) plus
 * the operation-specific detail fields. Fault beans and fault exceptions share
 * simple names across the contract packages, so the exception types are imported
 * here and the bean types are fully qualified on purpose.
 */
public final class ErpFaults {

    public static final String SOURCE_SUBSYSTEM_ORDERS = "ORDERS";
    public static final String SOURCE_SUBSYSTEM_INVENTORY = "INVENTORY";
    public static final String SOURCE_SUBSYSTEM_PRICING = "PRICING";

    public static final String CODE_ORDER_UNKNOWN = "ORD-UNKNOWN";
    public static final String CODE_ORDER_UNKNOWN_STORE = "ORD-UNKNOWN-STORE";
    public static final String CODE_ORDER_UNKNOWN_SKU = "ORD-UNKNOWN-SKU";
    public static final String CODE_ORDER_BAD_QUANTITY = "ORD-BAD-QUANTITY";
    public static final String CODE_ORDER_BAD_REF = "ORD-BAD-REF";
    public static final String CODE_ORDER_DUPLICATE_REF = "ORD-DUP-REF";
    public static final String CODE_ORDER_MISSING_AUDIT = "ORD-MISSING-AUDIT";

    public static final String CODE_INVENTORY_OUT_OF_STOCK = "INV-OUT-OF-STOCK";
    public static final String CODE_INVENTORY_UNKNOWN_STORE = "INV-UNKNOWN-STORE";
    public static final String CODE_INVENTORY_UNKNOWN_SKU = "INV-UNKNOWN-SKU";
    public static final String CODE_INVENTORY_BAD_QUANTITY = "INV-BAD-QUANTITY";
    public static final String CODE_INVENTORY_UNKNOWN_RESERVATION = "INV-UNKNOWN-RESERVATION";

    public static final String CODE_PRICING_SKU_UNKNOWN = "PRC-SKU-UNKNOWN";

    private ErpFaults() {
    }

    public static maple.erp.orders.v1.InvalidOrderFault invalidOrder(
            String errorCode, String message, String invalidField, String invalidValue, String externalOrderRef) {
        InvalidOrderFault detail = new InvalidOrderFault();
        populate(detail, errorCode, message, SOURCE_SUBSYSTEM_ORDERS);
        detail.setInvalidField(invalidField);
        // invalidValue is required-but-nillable in the contract; always send text.
        detail.setInvalidValue(invalidValue == null ? "" : invalidValue);
        if (externalOrderRef != null) {
            detail.setExternalOrderRef(externalOrderRef);
        }
        return new maple.erp.orders.v1.InvalidOrderFault(message, detail);
    }

    public static maple.erp.orders.v1.InvalidOrderFault orderUnknown(String orderId) {
        return invalidOrder(CODE_ORDER_UNKNOWN, "Unknown orderId: " + orderId, "orderId", orderId, null);
    }

    public static maple.erp.inventory.v1.OutOfStockFault outOfStock(
            String errorCode, String message, String storeId, String skuId, int requestedQuantity, int availableQuantity) {
        OutOfStockFault detail = new OutOfStockFault();
        populate(detail, errorCode, message, SOURCE_SUBSYSTEM_INVENTORY);
        detail.setStoreId(storeId);
        detail.setSkuId(skuId);
        detail.setRequestedQuantity(requestedQuantity);
        detail.setAvailableQuantity(availableQuantity);
        return new maple.erp.inventory.v1.OutOfStockFault(message, detail);
    }

    public static maple.erp.inventory.v1.UnknownReservationFault reservationUnknown(String reservationId) {
        String message = "Unknown reservationId: " + reservationId;
        UnknownReservationFault detail = new UnknownReservationFault();
        populate(detail, CODE_INVENTORY_UNKNOWN_RESERVATION, message, SOURCE_SUBSYSTEM_INVENTORY);
        detail.setReservationId(reservationId);
        return new maple.erp.inventory.v1.UnknownReservationFault(message, detail);
    }

    public static maple.erp.pricing.v1.UnknownSkuFault skuUnknown(String skuId) {
        String message = "Unknown SKU: " + skuId;
        UnknownSkuFault detail = new UnknownSkuFault();
        populate(detail, CODE_PRICING_SKU_UNKNOWN, message, SOURCE_SUBSYSTEM_PRICING);
        detail.setSkuId(skuId);
        return new maple.erp.pricing.v1.UnknownSkuFault(message, detail);
    }

    private static void populate(ErpFaultType detail, String errorCode, String message, String sourceSubsystem) {
        detail.setErrorCode(errorCode);
        detail.setErrorMessage(message);
        detail.setSourceSubsystem(sourceSubsystem);
        detail.setOccurredAt(XmlDateTimes.nowUtc());
    }
}

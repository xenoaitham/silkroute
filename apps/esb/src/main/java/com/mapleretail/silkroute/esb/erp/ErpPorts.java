package com.mapleretail.silkroute.esb.erp;

/**
 * The three typed ERP ports (generated read-only from the frozen WSDLs, C6).
 * A record so production wiring (CXF + WSS + timeouts) and test fakes share the
 * same shape.
 */
public record ErpPorts(
        maple.erp.orders.v1.OrderServicePortType orders,
        maple.erp.inventory.v1.InventoryServicePortType inventory,
        maple.erp.pricing.v1.PricingServicePortType pricing) {
}

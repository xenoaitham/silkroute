package com.mapleretail.silkroute.legacyerp.domain;

import com.mapleretail.silkroute.legacyerp.contract.orders.OrderLineStatusType;

/**
 * One stored order line. Line status starts ACCEPTED because the ERP validated
 * the whole order at submission time (rejected lines would have faulted the order).
 */
public record OrderLineRecord(String skuId, int quantity, OrderLineStatusType lineStatus) {
}

package com.mapleretail.silkroute.legacyerp.domain;

import java.time.Instant;
import java.util.List;

import com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderChannelType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderStatusType;

/**
 * A stored order. {@code totalAmountMinor} is in {@code currency} minor units
 * (constraint C5: integers only). New orders are SUBMITTED; lifecycle transitions
 * arrive from the (simulated) mainframe batch, not from this phase.
 */
public record OrderRecord(
        String orderId,
        String externalOrderRef,
        String sourceSystem,
        String storeId,
        OrderChannelType channel,
        OrderStatusType status,
        Instant placedAt,
        Instant lastUpdatedAt,
        long totalAmountMinor,
        CurrencyCodeType currency,
        List<OrderLineRecord> lines) {

    public OrderRecord {
        lines = List.copyOf(lines);
    }
}

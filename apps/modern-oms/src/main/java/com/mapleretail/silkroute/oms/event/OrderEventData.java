package com.mapleretail.silkroute.oms.event;

import java.util.List;

/**
 * Normalized view of one ESB success event (the OrderSubmissionResponse body
 * plus customerRef and lines[] — the event-copy-only fields, see
 * apps/esb EventPublisher). Money is ALWAYS integer minor units + currency
 * code (C5); line totals are integer multiplication performed here, never
 * floating point.
 *
 * C1: customerRef is carried VERBATIM — for CN stores the ESB already masked
 * it to msk-* before publishing; nothing in this codebase can or may unmask.
 */
public final class OrderEventData {

    private final String orderId;
    private final String externalOrderRef;
    private final String sourceSystem;
    private final String storeId;
    private final String region;
    private final String customerRef;
    private final String channel;
    private final String status;
    private final String reservationId;
    private final Long totalAmountMinor;
    private final String currency;
    private final String correlationId;
    private final List<Line> lines;
    private String rawEventJson;

    public OrderEventData(String orderId, String externalOrderRef, String sourceSystem, String storeId,
            String region, String customerRef, String channel, String status, String reservationId,
            Long totalAmountMinor, String currency, String correlationId, List<Line> lines) {
        this.orderId = orderId;
        this.externalOrderRef = externalOrderRef;
        this.sourceSystem = sourceSystem;
        this.storeId = storeId;
        this.region = region;
        this.customerRef = customerRef;
        this.channel = channel;
        this.status = status;
        this.reservationId = reservationId;
        this.totalAmountMinor = totalAmountMinor;
        this.currency = currency;
        this.correlationId = correlationId;
        this.lines = List.copyOf(lines);
    }

    public String getOrderId() {
        return orderId;
    }

    public String getExternalOrderRef() {
        return externalOrderRef;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getStoreId() {
        return storeId;
    }

    public String getRegion() {
        return region;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getChannel() {
        return channel;
    }

    public String getStatus() {
        return status;
    }

    public String getReservationId() {
        return reservationId;
    }

    public Long getTotalAmountMinor() {
        return totalAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public List<Line> getLines() {
        return lines;
    }

    /** Full original event JSON, kept for lineage (oms_order.raw_event). */
    public String getRawEventJson() {
        return rawEventJson;
    }

    public OrderEventData withRawEventJson(String rawEventJson) {
        this.rawEventJson = rawEventJson;
        return this;
    }

    /** One order line; lineTotalMinor = quantity x unitPriceMinor in long math (C5). */
    public record Line(String skuId, int quantity, long unitPriceMinor, String currency, long lineTotalMinor) {
        public static Line of(String skuId, int quantity, long unitPriceMinor, String currency) {
            return new Line(skuId, quantity, unitPriceMinor, currency, LineMath.lineTotal(quantity, unitPriceMinor));
        }
    }

    /** C5: integer minor-unit math only. Overflow is arithmetic long overflow — quantities are 1..999 and prices minor units, so realistic values stay far below Long.MAX_VALUE. */
    public static final class LineMath {
        private LineMath() {
        }

        public static long lineTotal(int quantity, long unitPriceMinor) {
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be >= 1, got " + quantity);
            }
            return (long) quantity * unitPriceMinor;
        }
    }
}

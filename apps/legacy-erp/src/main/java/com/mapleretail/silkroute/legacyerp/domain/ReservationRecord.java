package com.mapleretail.silkroute.legacyerp.domain;

import java.time.Instant;

/**
 * A stock hold placed by reserve(). Holds expire back to available after
 * {@code reservedUntil} (lazy reclaim on the next touch of the same stock row) —
 * matching the legacy hold-expiry semantics documented in the frozen contract.
 */
public record ReservationRecord(
        String reservationId,
        String storeId,
        String skuId,
        int quantity,
        Instant reservedUntil) {

    public ReservationRecord {
        if (quantity < 1) {
            throw new IllegalArgumentException("Reservation quantity must be >= 1: " + quantity);
        }
    }

    public ReservationRecord withQuantity(int newQuantity) {
        return new ReservationRecord(reservationId, storeId, skuId, newQuantity, reservedUntil);
    }
}

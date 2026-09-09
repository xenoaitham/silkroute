package com.mapleretail.silkroute.legacyerp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.legacyerp.ledger.InventoryLedger;

/**
 * Inventory invariants: availability is onHand minus reserved, the ledger never
 * over-allocates, release is exact, and expired holds return to available.
 */
class InventoryLedgerTest {

    private static final String STORE = "ST-CA-01";
    private static final String SKU = "SKU-0001";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-09T12:00:00Z"));
    private final InventoryLedger ledger = new InventoryLedger(clock);

    @Test
    void reserveDecrementsAvailableAndNeverOverAllocates() {
        ledger.seedRow(STORE, SKU, 10);

        InventoryLedger.ReserveAttempt first = ledger.tryReserve(STORE, SKU, 4);
        assertEquals(InventoryLedger.ReserveOutcome.RESERVED, first.outcome());
        assertEquals(6, first.remainingAvailableQuantity());

        InventoryLedger.ReserveAttempt second = ledger.tryReserve(STORE, SKU, 6);
        assertEquals(InventoryLedger.ReserveOutcome.RESERVED, second.outcome());
        assertEquals(0, second.remainingAvailableQuantity());

        InventoryLedger.ReserveAttempt third = ledger.tryReserve(STORE, SKU, 1);
        assertEquals(InventoryLedger.ReserveOutcome.INSUFFICIENT, third.outcome());
        assertEquals(1, third.requestedQuantity());
        assertEquals(0, third.availableQuantity());

        assertEquals(10, ledger.snapshot(STORE, SKU).reserved());
        assertEquals(0, ledger.snapshot(STORE, SKU).available());
    }

    @Test
    void releaseReturnsQuantityToAvailable() {
        ledger.seedRow(STORE, SKU, 10);
        String reservationId = ledger.tryReserve(STORE, SKU, 7).reservationId();

        InventoryLedger.ReleaseAttempt partial = ledger.release(reservationId, 3);
        assertEquals(InventoryLedger.ReleaseOutcome.RELEASED, partial.outcome());
        assertEquals(3, partial.releasedQuantity());
        assertEquals(6, partial.remainingAvailableQuantity());
        assertFalse(partial.fullyReleased());

        InventoryLedger.ReleaseAttempt rest = ledger.release(reservationId, null);
        assertEquals(4, rest.releasedQuantity());
        assertTrue(rest.fullyReleased());
        assertEquals(10, ledger.snapshot(STORE, SKU).available());
        assertEquals(0, ledger.snapshot(STORE, SKU).reserved());
    }

    @Test
    void releaseClampsToOutstandingAndUnknownIdsAreRejected() {
        ledger.seedRow(STORE, SKU, 10);
        String reservationId = ledger.tryReserve(STORE, SKU, 2).reservationId();

        InventoryLedger.ReleaseAttempt clamped = ledger.release(reservationId, 99);
        assertEquals(2, clamped.releasedQuantity());
        assertTrue(clamped.fullyReleased());

        assertEquals(InventoryLedger.ReleaseOutcome.UNKNOWN_RESERVATION,
                ledger.release("RES-2026-999999", null).outcome());
        assertEquals(InventoryLedger.ReleaseOutcome.UNKNOWN_RESERVATION,
                ledger.release(null, null).outcome());
    }

    @Test
    void expiredHoldsReturnToAvailableOnNextTouch() {
        ledger.seedRow(STORE, SKU, 10);
        String reservationId = ledger.tryReserve(STORE, SKU, 10).reservationId();
        assertNotNull(reservationId);
        assertEquals(0, ledger.snapshot(STORE, SKU).available());

        clock.advanceBy(Duration.ofHours(25));

        assertEquals(10, ledger.snapshot(STORE, SKU).available());
        assertEquals(0, ledger.snapshot(STORE, SKU).reserved());

        // The expired reservation is gone: an identical new reservation succeeds.
        assertEquals(InventoryLedger.ReserveOutcome.RESERVED, ledger.tryReserve(STORE, SKU, 5).outcome());
    }

    @Test
    void unknownStoreAndSkuAreDistinguished() {
        ledger.seedRow(STORE, SKU, 10);
        assertEquals(InventoryLedger.ReserveOutcome.UNKNOWN_STORE, ledger.tryReserve("ST-XX-99", SKU, 1).outcome());
        assertEquals(InventoryLedger.ReserveOutcome.UNKNOWN_SKU, ledger.tryReserve(STORE, "SKU-9999", 1).outcome());
        assertNull(ledger.snapshot("ST-XX-99", SKU));
        assertNull(ledger.snapshot(STORE, "SKU-9999"));
    }

    /** Mutable clock for deterministic hold-expiry testing. */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advanceBy(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

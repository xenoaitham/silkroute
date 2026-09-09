package com.mapleretail.silkroute.legacyerp.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.legacyerp.domain.ReservationRecord;

/**
 * In-memory stock ledger: on-hand quantities per store x SKU plus the open
 * reservations against them.
 *
 * <p>Never over-allocates: reserve() checks and decrements availability inside a
 * single per-row critical section (the StockRow monitor), so concurrent reserves
 * cannot both succeed past the available count. Holds expire back to available
 * (lazy reclaim on the next touch of the affected row) after the 24h hold window.
 */
@Component
public class InventoryLedger {

    public static final Duration RESERVATION_HOLD = Duration.ofHours(24);
    public static final String RESERVATION_ID_YEAR = "2026";
    public static final int RESERVATION_ID_DIGITS = 6;

    private final Map<String, Map<String, StockRow>> rowsByStoreId = new ConcurrentHashMap<>();
    private final Map<String, ReservationRecord> reservationsById = new ConcurrentHashMap<>();
    private final AtomicLong reservationSequence = new AtomicLong();
    private final Clock clock;

    public InventoryLedger(Clock clock) {
        this.clock = clock;
    }

    public void seedRow(String storeId, String skuId, int onHandQuantity) {
        rowsByStoreId.computeIfAbsent(storeId, key -> new ConcurrentHashMap<>())
                .put(skuId, new StockRow(onHandQuantity));
    }

    public int stockRowCount() {
        return rowsByStoreId.values().stream().mapToInt(Map::size).sum();
    }

    /** Returns null when the store or SKU has no stock row. */
    public StockSnapshot snapshot(String storeId, String skuId) {
        StockRow row = findRow(storeId, skuId);
        if (row == null) {
            return null;
        }
        synchronized (row) {
            reclaimExpiredHolds(storeId, skuId, row);
            return new StockSnapshot(row.onHand, row.reserved, row.onHand - row.reserved);
        }
    }

    public ReserveAttempt tryReserve(String storeId, String skuId, int quantity) {
        if (!rowsByStoreId.containsKey(storeId)) {
            return ReserveAttempt.failed(ReserveOutcome.UNKNOWN_STORE, quantity, 0);
        }
        StockRow row = findRow(storeId, skuId);
        if (row == null) {
            return ReserveAttempt.failed(ReserveOutcome.UNKNOWN_SKU, quantity, 0);
        }
        synchronized (row) {
            reclaimExpiredHolds(storeId, skuId, row);
            int available = row.onHand - row.reserved;
            if (quantity > available) {
                return ReserveAttempt.failed(ReserveOutcome.INSUFFICIENT, quantity, available);
            }
            row.reserved += quantity;
            Instant reservedUntil = Instant.now(clock).plus(RESERVATION_HOLD);
            String reservationId = nextReservationId();
            reservationsById.put(reservationId,
                    new ReservationRecord(reservationId, storeId, skuId, quantity, reservedUntil));
            return ReserveAttempt.reserved(reservationId, quantity, available - quantity, reservedUntil);
        }
    }

    /**
     * Releases part (or all) of a reservation. A null/non-positive quantity means
     * "release everything outstanding"; an over-large partial quantity is clamped
     * to what is actually outstanding — the contract offers no typed fault for
     * this case and the ERP sim never releases more than was reserved.
     */
    public ReleaseAttempt release(String reservationId, Integer quantity) {
        ReservationRecord record = reservationId == null ? null : reservationsById.get(reservationId);
        if (record == null) {
            return ReleaseAttempt.unknownReservation();
        }
        StockRow row = findRow(record.storeId(), record.skuId());
        if (row == null) {
            // Cannot happen: reservations only reference seeded rows. Defensive
            // fallback so a ledger bug can never over-allocate silently.
            return ReleaseAttempt.unknownReservation();
        }
        synchronized (row) {
            reclaimExpiredHolds(record.storeId(), record.skuId(), row);
            record = reservationsById.get(reservationId);
            if (record == null) {
                // Expired and reclaimed between the lookup and the lock.
                return ReleaseAttempt.unknownReservation();
            }
            int outstanding = record.quantity();
            int released = (quantity == null || quantity < 1) ? outstanding : Math.min(quantity, outstanding);
            row.reserved -= released;
            boolean fullyReleased = released == outstanding;
            if (fullyReleased) {
                reservationsById.remove(reservationId);
            } else {
                reservationsById.put(reservationId, record.withQuantity(outstanding - released));
            }
            return new ReleaseAttempt(ReleaseOutcome.RELEASED, released, row.onHand - row.reserved, fullyReleased);
        }
    }

    /**
     * Caller must hold the row monitor: reclaims expired holds for this exact
     * store+SKU pair, moving their quantity back to available.
     */
    private void reclaimExpiredHolds(String storeId, String skuId, StockRow row) {
        Instant now = Instant.now(clock);
        Iterator<ReservationRecord> iterator = reservationsById.values().iterator();
        while (iterator.hasNext()) {
            ReservationRecord record = iterator.next();
            if (record.storeId().equals(storeId)
                    && record.skuId().equals(skuId)
                    && record.reservedUntil().isBefore(now)) {
                row.reserved -= record.quantity();
                iterator.remove();
            }
        }
    }

    private String nextReservationId() {
        return ("RES-" + RESERVATION_ID_YEAR + "-%0" + RESERVATION_ID_DIGITS + "d")
                .formatted(reservationSequence.incrementAndGet());
    }

    private StockRow findRow(String storeId, String skuId) {
        Map<String, StockRow> rowsForStore = rowsByStoreId.get(storeId);
        return rowsForStore == null ? null : rowsForStore.get(skuId);
    }

    public enum ReserveOutcome {
        RESERVED, INSUFFICIENT, UNKNOWN_STORE, UNKNOWN_SKU
    }

    public enum ReleaseOutcome {
        RELEASED, UNKNOWN_RESERVATION
    }

    /**
     * Immutable stock reading. {@code available} is always {@code onHand - reserved}
     * — the ledger never lets that go negative.
     */
    public record StockSnapshot(int onHand, int reserved, int available) {
    }

    public record ReserveAttempt(
            ReserveOutcome outcome,
            String reservationId,
            int requestedQuantity,
            int availableQuantity,
            int remainingAvailableQuantity,
            Instant reservedUntil) {

        static ReserveAttempt reserved(String reservationId, int requestedQuantity,
                                       int remainingAvailable, Instant reservedUntil) {
            return new ReserveAttempt(ReserveOutcome.RESERVED, reservationId, requestedQuantity,
                    remainingAvailable, remainingAvailable, reservedUntil);
        }

        static ReserveAttempt failed(ReserveOutcome outcome, int requestedQuantity, int availableQuantity) {
            return new ReserveAttempt(outcome, null, requestedQuantity, availableQuantity, availableQuantity, null);
        }
    }

    public record ReleaseAttempt(
            ReleaseOutcome outcome,
            int releasedQuantity,
            int remainingAvailableQuantity,
            boolean fullyReleased) {

        static ReleaseAttempt unknownReservation() {
            return new ReleaseAttempt(ReleaseOutcome.UNKNOWN_RESERVATION, 0, 0, false);
        }
    }

    /** Mutable stock cell; all access happens under this instance's monitor. */
    private static final class StockRow {
        private final int onHand;
        private int reserved;

        private StockRow(int onHand) {
            if (onHand < 0) {
                throw new IllegalArgumentException("onHand must be >= 0: " + onHand);
            }
            this.onHand = onHand;
        }
    }
}

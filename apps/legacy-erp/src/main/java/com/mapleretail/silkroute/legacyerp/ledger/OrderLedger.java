package com.mapleretail.silkroute.legacyerp.ledger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.legacyerp.domain.OrderRecord;

/**
 * In-memory order store (recorded decision: no database — the ERP's real store
 * stays behind the mainframe). Enforces externalOrderRef uniqueness per
 * sourceSystem and issues sequential ORD-2026-NNNNNN ids. State resets on
 * restart, which is acceptable for the sim.
 */
@Component
public class OrderLedger {

    public static final String ORDER_ID_YEAR = "2026";
    public static final int ORDER_ID_DIGITS = 6;

    private final Map<String, OrderRecord> ordersById = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> orderIdsBySourceSystemAndRef = new ConcurrentHashMap<>();
    private final AtomicLong orderSequence = new AtomicLong();

    public String nextOrderId() {
        return ("ORD-" + ORDER_ID_YEAR + "-%0" + ORDER_ID_DIGITS + "d")
                .formatted(orderSequence.incrementAndGet());
    }

    /**
     * Registers a validated order. Returns the pre-existing order id when the
     * (sourceSystem, externalOrderRef) pair was already used — callers turn that
     * into a typed ORD-DUP-REF fault.
     */
    public Optional<String> tryRegister(OrderRecord record) {
        Map<String, String> refsForSource =
                orderIdsBySourceSystemAndRef.computeIfAbsent(record.sourceSystem(), key -> new ConcurrentHashMap<>());
        String existingOrderId = refsForSource.putIfAbsent(record.externalOrderRef(), record.orderId());
        if (existingOrderId != null) {
            return Optional.of(existingOrderId);
        }
        ordersById.put(record.orderId(), record);
        return Optional.empty();
    }

    public OrderRecord find(String orderId) {
        return orderId == null ? null : ordersById.get(orderId);
    }

    public int count() {
        return ordersById.size();
    }
}

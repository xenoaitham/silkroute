package com.mapleretail.silkroute.oms.store;

import com.mapleretail.silkroute.oms.event.OrderEventData;
import com.mapleretail.silkroute.oms.event.RecordMeta;

/**
 * The event-store port: one ESB success event -> oms_order + oms_order_line.
 * Implementations MUST be idempotent per business key (source_system,
 * external_order_ref): a replay returns {@link Outcome#replaySkipped()} true
 * and writes nothing (no partial duplicate lines either).
 */
public interface OrderEventStore {

    Outcome store(OrderEventData event, RecordMeta meta);

    record Outcome(boolean replaySkipped) {
        public static Outcome stored() {
            return new Outcome(false);
        }

        public static Outcome skipped() {
            return new Outcome(true);
        }
    }
}

package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalAudit;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalLine;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.idem.IdempotencyKeys;
import com.mapleretail.silkroute.esb.idem.IdempotencyStore;

/**
 * Idempotent-consumer logic with a FAKE store (no Redis needed): claim/dup
 * semantics, completed-outcome storage, and key derivation.
 */
class IdempotencyLogicTest {

    /** In-memory fake mirroring the Redis SETNX semantics. */
    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        private final Map<String, String> inflight = new HashMap<>();
        private final Map<String, String> done = new HashMap<>();

        @Override
        public boolean claim(String key) {
            return inflight.putIfAbsent(key, "INFLIGHT") == null;
        }

        @Override
        public Optional<String> getCompleted(String key) {
            return Optional.ofNullable(done.get(key));
        }

        @Override
        public void storeCompleted(String key, String finalResponseJson) {
            done.put(key, finalResponseJson);
        }

        @Override
        public void release(String key) {
            inflight.remove(key);
        }
    }

    private CanonicalOrder order(String sourceSystem, String externalOrderRef) {
        CanonicalOrder order = new CanonicalOrder();
        order.setSourceSystem(sourceSystem);
        order.setExternalOrderRef(externalOrderRef);
        order.setStoreId("ST-CA-01");
        order.setChannel("WEB_STORE");
        order.setLines(java.util.List.of(new CanonicalLine("SKU-0001", 1)));
        CanonicalAudit audit = new CanonicalAudit();
        audit.setSourceSystem(sourceSystem);
        audit.setReceivedAt("2026-09-11T10:00:00.000Z");
        audit.setCorrelationId("corr-idem-1");
        order.setAudit(audit);
        return order;
    }

    @Test
    void firstClaimWinsSecondIsDuplicate() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        assertTrue(store.claim("k-1"));
        assertFalse(store.claim("k-1"), "second claim for the same key must be a duplicate");
        assertTrue(store.claim("k-2"));
    }

    @Test
    void completedOutcomeIsStoredAndReturned() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.claim("k-1");
        assertTrue(store.getCompleted("k-1").isEmpty(), "claimed but not completed yet");
        store.storeCompleted("k-1", "{\"orderId\":\"ORD-2026-000001\"}");
        assertEquals("{\"orderId\":\"ORD-2026-000001\"}", store.getCompleted("k-1").orElseThrow());
    }

    @Test
    void headerKeyIsUsedVerbatim() {
        assertEquals("client-provided-key", IdempotencyKeys.derive("client-provided-key", order("SRC", "REF-1")));
        assertEquals("trimmed-key", IdempotencyKeys.derive("  trimmed-key  ", order("SRC", "REF-1")));
    }

    @Test
    void derivedKeyIsSha256OfSourceSystemPlusExternalOrderRef() {
        String key = IdempotencyKeys.derive(null, order("WEB_STORE_CA", "WEB-1"));
        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"), "hex sha256: " + key);
        assertEquals(key, IdempotencyKeys.derive("", order("WEB_STORE_CA", "WEB-1")),
                "blank header behaves like absent");
        assertFalse(key.equals(IdempotencyKeys.derive(null, order("WEB_STORE_CA", "WEB-2"))),
                "different orders derive different keys");
    }
}

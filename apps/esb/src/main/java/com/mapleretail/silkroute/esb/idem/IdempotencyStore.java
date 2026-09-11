package com.mapleretail.silkroute.esb.idem;

import java.util.Optional;

/**
 * Idempotent-consumer storage seam (Redis in production, in-memory fake in
 * unit tests). Keys are the raw Idempotency-Key header value or the derived
 * sha256(sourceSystem + externalOrderRef).
 */
public interface IdempotencyStore {

    /** Atomically claims processing for the key; false means duplicate. */
    boolean claim(String key);

    /** The stored final response JSON for a completed earlier request, if any. */
    Optional<String> getCompleted(String key);

    /** Persists the final response JSON after a successful saga (TTL 24h). */
    void storeCompleted(String key, String finalResponseJson);

    /**
     * Releases a claim whose saga did NOT succeed (compensated failure, business
     * fault, infra exhaustion): the client is allowed to retry the same key. A
     * claim for a FAILED outcome must never lock the key out for the TTL.
     */
    void release(String key);
}

package com.mapleretail.silkroute.esb.idem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;

/**
 * Idempotency key derivation: the Idempotency-Key request header when present,
 * else sha256(sourceSystem + externalOrderRef) hex.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    private IdempotencyKeys() {
    }

    public static String derive(String idempotencyKeyHeader, CanonicalOrder order) {
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            return idempotencyKeyHeader.trim();
        }
        String source = order.getSourceSystem() == null ? "" : order.getSourceSystem();
        String ref = order.getExternalOrderRef() == null ? "" : order.getExternalOrderRef();
        return sha256Hex(source + ref);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

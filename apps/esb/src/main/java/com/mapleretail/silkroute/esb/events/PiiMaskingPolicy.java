package com.mapleretail.silkroute.esb.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

/**
 * C1/PIPL policy point (PII residency): Chinese-customer PII must remain in the
 * China region. ANY egress of order data from the CN region to a SHARED
 * (non-CN-pinned) destination — the hub's Kafka event topic and DLQ — must carry
 * a masked customerRef.
 *
 * Masking format: "msk-" + first 12 hex chars of SHA-256(customerRef)
 * (deterministic, so analytics can still correlate without re-identifying).
 * Non-CN customerRef stays clear; absent stays absent.
 *
 * Full residency proof (region-pinned storage) is Phase 5; this class is the
 * enforced hook every shared-topic publisher MUST go through.
 */
@Component
public class PiiMaskingPolicy {

    public static final String CN = "CN";
    public static final String MASK_PREFIX = "msk-";
    private static final int MASK_HEX_CHARS = 12;

    /** True when customerRef egressing from this region must be masked. */
    public boolean requiresMasking(String region) {
        return CN.equals(region);
    }

    /** True when masking was applied to THIS value on THIS egress. */
    public boolean applied(String customerRef, String region) {
        return customerRef != null && !customerRef.isBlank() && requiresMasking(region);
    }

    /**
     * Egress transformation for customerRef: CN → "msk-" + sha256[0..11] hex,
     * non-CN → unchanged, null/blank → unchanged.
     */
    public String maskForEgress(String customerRef, String region) {
        if (!applied(customerRef, region)) {
            return customerRef;
        }
        return MASK_PREFIX + sha256Hex(customerRef).substring(0, MASK_HEX_CHARS);
    }

    /** Deterministic audit helper: the lowercase hex SHA-256 of the value. */
    public static String sha256Hex(String value) {
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

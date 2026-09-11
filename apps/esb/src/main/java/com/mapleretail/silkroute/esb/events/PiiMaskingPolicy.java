package com.mapleretail.silkroute.esb.events;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * C1/PIPL policy point (PII residency): Chinese-customer PII must remain in the
 * China region. ANY egress of order data from the CN region to a SHARED
 * (non-CN-pinned) destination — the hub's Kafka event topic and DLQ — must carry
 * a pseudonymized customerRef.
 *
 * Pseudonymization format: "msk-" + first 12 hex chars of
 * HMAC-SHA256(egress-secret, customerRef). Keyed with an ESB-held secret so the
 * transformation is deterministic (analytics can still correlate) but NOT
 * dictionary-reversible for guessable reference patterns. This is
 * PSEUDONYMIZATION, not anonymization — the secret-holder could brute-force
 * known values; true anonymization/residency is Phase 5 scope.
 * Non-CN customerRef stays clear; absent stays absent.
 */
@Component
public class PiiMaskingPolicy {

    public static final String CN = "CN";
    public static final String MASK_PREFIX = "msk-";
    private static final int MASK_HEX_CHARS = 12;

    private final byte[] secret;

    public PiiMaskingPolicy(@Value("${esb.masking.secret:${PII_MASK_SECRET:sim-egress-secret}}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** True when customerRef egressing from this region must be masked. */
    public boolean requiresMasking(String region) {
        return CN.equals(region);
    }

    /** True when masking must be applied to THIS value on THIS egress. */
    public boolean wouldMask(String customerRef, String region) {
        return customerRef != null && !customerRef.isBlank() && requiresMasking(region);
    }

    /**
     * Egress transformation for customerRef: CN → "msk-" + hmacSHA256[0..11] hex,
     * non-CN → unchanged, null/blank → unchanged.
     */
    public String maskForEgress(String customerRef, String region) {
        if (!wouldMask(customerRef, region)) {
            return customerRef;
        }
        return MASK_PREFIX + hmacHex(customerRef).substring(0, MASK_HEX_CHARS);
    }

    /** Deterministic, keyed: lowercase hex HMAC-SHA256 of the value. */
    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

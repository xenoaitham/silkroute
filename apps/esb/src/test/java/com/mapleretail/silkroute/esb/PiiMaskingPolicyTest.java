package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.esb.events.PiiMaskingPolicy;

/**
 * C1/PIPL policy hook: CN-region customerRef must be pseudonymized BEFORE egress
 * to shared (non-CN-pinned) destinations; non-CN stays clear. The transform is
 * keyed HMAC-SHA256 — deterministic for correlation, not dictionary-reversible
 * for guessable refs (and NOT anonymization; see the class javadoc).
 */
class PiiMaskingPolicyTest {

    private final PiiMaskingPolicy policy = new PiiMaskingPolicy("test-egress-secret");

    @Test
    void masksCnCustomerRefAsMskPlus12HexOfKeyedHmac() {
        String masked = policy.maskForEgress("cust-zhang-888", "CN");
        assertTrue(masked.startsWith("msk-"), "masked value must carry the msk- prefix: " + masked);
        String suffix = masked.substring(4);
        assertTrue(suffix.matches("[0-9a-f]{12}"), "suffix must be lowercase hex: " + suffix);
        assertEquals(4 + 12, masked.length(), "mask = 'msk-' + 12 hex chars");
        // Keyed: the same input + same secret gives the same mask...
        assertEquals(policy.maskForEgress("cust-zhang-888", "CN"), masked);
        // ...but it is NOT the plain (unkeyed) hash of the input.
        assertNotEquals(PiiMaskingPolicyTestStatics.plainSha256Prefix("cust-zhang-888"), suffix,
                "mask must be keyed, not a plain dictionary-buildable hash");
    }

    @Test
    void maskingIsDeterministicAndInputSensitive() {
        assertEquals(policy.maskForEgress("cust-1", "CN"), policy.maskForEgress("cust-1", "CN"));
        assertFalse(policy.maskForEgress("cust-1", "CN").equals(policy.maskForEgress("cust-2", "CN")));
    }

    @Test
    void differentSecretMasksDifferently() {
        PiiMaskingPolicy otherKey = new PiiMaskingPolicy("a-different-secret");
        assertNotEquals(policy.maskForEgress("cust-778811", "CN"), otherKey.maskForEgress("cust-778811", "CN"),
                "the mask must depend on the held secret");
    }

    @Test
    void maskedValueCannotContainTheOriginal() {
        String masked = policy.maskForEgress("customer-42", "CN");
        assertFalse(masked.contains("customer-42"));
    }

    @Test
    void nonCnStaysClear() {
        assertEquals("cust-abc", policy.maskForEgress("cust-abc", "CA"));
        assertEquals("cust-abc", policy.maskForEgress("cust-abc", "SG"));
        assertFalse(policy.requiresMasking("CA"));
        assertFalse(policy.requiresMasking("SG"));
        assertTrue(policy.requiresMasking("CN"));
    }

    @Test
    void nullAndBlankStayUnchanged() {
        assertNull(policy.maskForEgress(null, "CN"));
        assertEquals("", policy.maskForEgress("", "CN"));
        assertFalse(policy.wouldMask(null, "CN"));
        assertFalse(policy.wouldMask("  ", "CN"));
        assertFalse(policy.wouldMask("cust-1", "CA"));
        assertTrue(policy.wouldMask("cust-1", "CN"));
    }

    /** Unkeyed reference hash, only to prove the production mask is NOT it. */
    static final class PiiMaskingPolicyTestStatics {
        static String plainSha256Prefix(String value) {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                    hex.append(Character.forDigit(b & 0xF, 16));
                }
                return hex.substring(0, 12);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

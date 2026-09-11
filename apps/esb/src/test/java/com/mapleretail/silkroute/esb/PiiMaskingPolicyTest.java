package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.esb.events.PiiMaskingPolicy;

/**
 * C1/PIPL policy hook: CN-region customerRef must be masked BEFORE egress to
 * shared (non-CN-pinned) destinations; non-CN stays clear.
 */
class PiiMaskingPolicyTest {

    private final PiiMaskingPolicy policy = new PiiMaskingPolicy();

    @Test
    void masksCnCustomerRefAsMskPlus12HexOfSha256() {
        String masked = policy.maskForEgress("cust-zhang-888", "CN");
        assertTrue(masked.startsWith("msk-"), "masked value must carry the msk- prefix: " + masked);
        assertEquals("msk-", masked.substring(0, 4));
        assertEquals(4 + 12, masked.length(), "mask = 'msk-' + 12 hex chars");
        String suffix = masked.substring(4);
        assertTrue(suffix.matches("[0-9a-f]{12}"), "suffix must be lowercase hex: " + suffix);
        assertEquals(suffix, PiiMaskingPolicy.sha256Hex("cust-zhang-888").substring(0, 12));
    }

    @Test
    void maskingIsDeterministicAndInputSensitive() {
        assertEquals(policy.maskForEgress("cust-1", "CN"), policy.maskForEgress("cust-1", "CN"));
        assertFalse(policy.maskForEgress("cust-1", "CN").equals(policy.maskForEgress("cust-2", "CN")));
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
        assertFalse(policy.applied(null, "CN"));
        assertFalse(policy.applied("  ", "CN"));
    }
}

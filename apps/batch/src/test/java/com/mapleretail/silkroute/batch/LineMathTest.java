package com.mapleretail.silkroute.batch;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineMathTest {

    @Test
    void integerMinorUnitMultiplicationIsExact() {
        assertEquals(25000L, LineMath.lineTotal(2, 12500L));
        assertEquals(9999L, LineMath.lineTotal(3, 3333L));
        assertEquals(7L, LineMath.lineTotal(7, 1L));
        assertEquals(299_999_700L, LineMath.lineTotal(999, 300_300L));
    }

    @Test
    void zeroOrNegativeQuantityIsRejected() {
        try {
            LineMath.lineTotal(0, 100L);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}

package com.mapleretail.silkroute.batch;

/**
 * C5: line totals are INTEGER minor-unit multiplication — long math only,
 * never float/double. Used by the selftest fixture builder and pinned by
 * unit tests; the Spark column expression mirrors this exact semantics
 * (both operands cast to long BEFORE multiplying).
 */
public final class LineMath {

    private LineMath() {
    }

    public static long lineTotal(int quantity, long unitPriceMinor) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1, got " + quantity);
        }
        return (long) quantity * unitPriceMinor;
    }
}

package com.mapleretail.silkroute.legacyerp.domain;

/**
 * A catalog SKU. {@code basePriceCadMinor} is the price source of truth in CAD
 * minor units (constraint C5); all other currencies are derived from it.
 */
public record SkuRecord(
        String skuId,
        String name,
        String category,
        long basePriceCadMinor,
        boolean discountEligible) {
}

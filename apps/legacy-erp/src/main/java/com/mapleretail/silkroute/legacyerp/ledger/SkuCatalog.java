package com.mapleretail.silkroute.legacyerp.ledger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.legacyerp.domain.SkuRecord;

/**
 * In-memory SKU catalog. Populated once at boot by the seed data.
 */
@Component
public class SkuCatalog {

    private final Map<String, SkuRecord> skusById = new ConcurrentHashMap<>();

    public void register(SkuRecord sku) {
        skusById.put(sku.skuId(), sku);
    }

    /** Returns null when unknown — callers decide which typed fault fits. */
    public SkuRecord find(String skuId) {
        return skuId == null ? null : skusById.get(skuId);
    }

    public int count() {
        return skusById.size();
    }

    public List<SkuRecord> all() {
        return skusById.values().stream()
                .sorted(Comparator.comparing(SkuRecord::skuId))
                .toList();
    }
}

package com.mapleretail.silkroute.legacyerp.ledger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.legacyerp.domain.StoreRecord;

/**
 * In-memory store directory. The region of each store is load-bearing for order
 * pricing (CA->CAD, SG->SGD, CN->CNY) and for Phase 2 content-based routing.
 */
@Component
public class StoreDirectory {

    private final Map<String, StoreRecord> storesById = new ConcurrentHashMap<>();

    public void register(StoreRecord store) {
        storesById.put(store.storeId(), store);
    }

    /** Returns null when unknown — callers decide which typed fault fits. */
    public StoreRecord find(String storeId) {
        return storeId == null ? null : storesById.get(storeId);
    }

    public int count() {
        return storesById.size();
    }

    public List<StoreRecord> all() {
        return storesById.values().stream()
                .sorted(Comparator.comparing(StoreRecord::storeId))
                .toList();
    }
}

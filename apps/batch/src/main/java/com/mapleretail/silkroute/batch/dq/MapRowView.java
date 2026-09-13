package com.mapleretail.silkroute.batch.dq;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Simple Map-backed RowView (tests, selftest, and the Spark driver-side DQ adapter). */
public final class MapRowView implements RowView {

    private final Map<String, Object> values;

    private MapRowView(Map<String, Object> values) {
        this.values = values;
    }

    public static MapRowView of(Map<String, Object> values) {
        // HashMap (not Map.copyOf): DQ fixtures legitimately carry NULL column values
        return new MapRowView(java.util.Collections.unmodifiableMap(new HashMap<>(values)));
    }

    @Override
    public Object get(String column) {
        return values.get(column);
    }

    @Override
    public Set<String> columns() {
        return values.keySet();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}

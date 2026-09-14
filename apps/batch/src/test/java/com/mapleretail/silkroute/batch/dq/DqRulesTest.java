package com.mapleretail.silkroute.batch.dq;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.batch.dq.rules.LineCompletenessRule;
import com.mapleretail.silkroute.batch.dq.rules.OrderCompletenessRule;
import com.mapleretail.silkroute.batch.dq.rules.ReferentialKeysRule;
import com.mapleretail.silkroute.batch.dq.rules.UniqueKeysRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DQ predicates pinned as JUnit (the selftest runs the same classes;
 * these tests prove each rule catches its fixture violation - a check that
 * cannot fail is not a check).
 */
class DqRulesTest {

    private static RowView order(String orderId, String storeId) {
        return rowView("order_id", orderId, "store_id", storeId, "region", "CA",
                "total_amount_minor", 12500L, "currency", "CAD");
    }

    /** HashMap-backed fixture builder: null values are legitimate DQ inputs (Map.of rejects them). */
    private static RowView rowView(Object... keyValues) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return MapRowView.of(map);
    }

    @Test
    void orderCompletenessCatchesMissingStoreId() {
        OrderCompletenessRule rule = new OrderCompletenessRule();
        assertNull(rule.check(order("ORD-1", "ST-CA-01")));
        Violation violation = rule.check(rowView("order_id", "ORD-2", "store_id", null, "region", "CA",
                "total_amount_minor", 500L, "currency", "CAD"));
        assertNotNull(violation);
        assertEquals("DQ-COMPLETE", violation.ruleId());
        assertEquals("ORD-2", violation.rowKey());
        assertTrue(violation.reason().contains("store_id"));
    }

    @Test
    void lineCompletenessCatchesQuantityZero() {
        LineCompletenessRule rule = new LineCompletenessRule();
        assertNull(rule.check(rowView("order_id", "ORD-1", "line_no", 0,
                "sku_id", "SKU-0001", "quantity", 2, "unit_price_minor", 6250L, "currency", "CAD")));
        Violation violation = rule.check(rowView("order_id", "ORD-2", "line_no", 0,
                "sku_id", "SKU-0002", "quantity", 0, "unit_price_minor", 100L, "currency", "CAD"));
        assertNotNull(violation);
        assertEquals("quantity must be > 0, got 0", violation.reason());
    }

    @Test
    void uniquenessCatchesTheSecondOccurrenceOnly() {
        UniqueKeysRule rule = new UniqueKeysRule("fact_orders", List.of("order_id"), List.of("order_id"));
        assertNull(rule.check(order("ORD-1", "ST-CA-01")));
        Violation violation = rule.check(order("ORD-1", "ST-CA-01"));
        assertNotNull(violation);
        assertEquals("ORD-1", violation.rowKey());
        // lines variant: composite key
        UniqueKeysRule lineRule = new UniqueKeysRule("fact_order_lines",
                List.of("order_id", "line_no"), List.of("order_id", "line_no"));
        RowView line = MapRowView.of(Map.of("order_id", "ORD-1", "line_no", 0, "sku_id", "SKU-0001",
                "quantity", 2, "unit_price_minor", 6250L, "currency", "CAD"));
        assertNull(lineRule.check(line));
        assertNotNull(lineRule.check(line));
    }

    @Test
    void referentialCatchesOrphanLinesAndUnknownStores() {
        ReferentialKeysRule lines = new ReferentialKeysRule("fact_order_lines", "order_id", "fact_orders",
                Set.of("ORD-1"), List.of("order_id", "line_no"));
        Violation orphan = lines.check(MapRowView.of(Map.of("order_id", "ORD-99", "line_no", 0,
                "sku_id", "SKU-0003", "quantity", 1, "unit_price_minor", 100L, "currency", "CAD")));
        assertNotNull(orphan);
        assertEquals("ORD-99|0", orphan.rowKey());
        assertNull(lines.check(MapRowView.of(Map.of("order_id", "ORD-1", "line_no", 0,
                "sku_id", "SKU-0001", "quantity", 1, "unit_price_minor", 100L, "currency", "CAD"))));

        ReferentialKeysRule stores = new ReferentialKeysRule("fact_orders", "store_id", "dim_store",
                Set.of("ST-CA-01"), List.of("order_id"));
        assertNotNull(stores.check(order("ORD-4", "ST-ZZ-99")));
    }

    @Test
    void runnerCountsCheckedAndViolationsPerRule() {
        List<RowView> rows = List.of(order("ORD-1", "ST-CA-01"), order("ORD-1", "ST-CA-01"));
        List<DqResult> results = DqRunner.run("fact_orders", rows, List.of(
                new OrderCompletenessRule(),
                new UniqueKeysRule("fact_orders", List.of("order_id"), List.of("order_id"))));
        assertEquals(2, results.size());
        assertEquals(0, results.get(0).violations());
        assertEquals("PASS", results.get(0).status());
        assertEquals(1, results.get(1).violations());
        assertEquals("FAIL", results.get(1).status());
    }
}

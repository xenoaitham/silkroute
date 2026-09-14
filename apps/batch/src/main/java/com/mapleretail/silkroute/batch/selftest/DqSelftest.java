package com.mapleretail.silkroute.batch.selftest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapleretail.silkroute.batch.dq.DqResult;
import com.mapleretail.silkroute.batch.dq.DqRunner;
import com.mapleretail.silkroute.batch.dq.MapRowView;
import com.mapleretail.silkroute.batch.dq.RowView;
import com.mapleretail.silkroute.batch.dq.rules.LineCompletenessRule;
import com.mapleretail.silkroute.batch.dq.rules.OrderCompletenessRule;
import com.mapleretail.silkroute.batch.dq.rules.ReferentialKeysRule;
import com.mapleretail.silkroute.batch.dq.rules.UniqueKeysRule;

/**
 * `selftest` mode - the negative control for the DQ layer itself (mirrors
 * scripts/residency-tests.sh --selftest and make audit-demo precedent):
 * ONE JVM process, NO MySQL/MinIO/Spark dependency, fixtures with KNOWN
 * violations, run through the SAME pure predicate classes the pipeline uses.
 *
 * Exit 0 iff EVERY rule caught its own fixture violation; exit 2 if any rule
 * missed - a check that cannot fail is not a check.
 *
 * Fixtures (each rule gets a dedicated one):
 *   DQ-COMPLETE(orders)  : order missing store_id
 *   DQ-UNIQUE(orders)    : a duplicated order_id
 *   DQ-REFERENTIAL(orders): an order pointing at an unknown store
 *   DQ-COMPLETE(lines)   : a line with quantity 0
 *   DQ-UNIQUE(lines)     : a duplicated (order_id, line_no)
 *   DQ-REFERENTIAL(lines): an orphan line whose order_id is absent
 */
public final class DqSelftest {

    private static final Logger LOG = LoggerFactory.getLogger(DqSelftest.class);

    private DqSelftest() {
    }

    /** HashMap-backed fixture builder: null column values are legitimate DQ inputs (Map.of rejects them). */
    private static RowView rowView(Object... keyValues) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return MapRowView.of(map);
    }

    public static int run() {
        // ---- fact_orders fixtures ----
        Set<Object> dimStoreKeys = Set.of("ST-CA-01");
        List<RowView> orders = List.of(
                rowView("order_id", "ORD-1", "store_id", "ST-CA-01", "region", "CA",
                        "total_amount_minor", 12500L, "currency", "CAD"), // valid
                rowView("order_id", "ORD-2", "store_id", null, "region", "CA",
                        "total_amount_minor", 500L, "currency", "CAD"), // DQ-COMPLETE
                rowView("order_id", "ORD-1", "store_id", "ST-CA-01", "region", "CA",
                        "total_amount_minor", 12500L, "currency", "CAD"), // DQ-UNIQUE (dup pk)
                rowView("order_id", "ORD-4", "store_id", "ST-ZZ-99", "region", "ZZ",
                        "total_amount_minor", 100L, "currency", "XXX")); // DQ-REFERENTIAL (unknown store)

        // ---- fact_order_lines fixtures ----
        Set<Object> factOrderKeys = Set.of("ORD-1", "ORD-4");
        List<RowView> lines = List.of(
                rowView("order_id", "ORD-1", "line_no", 0, "sku_id", "SKU-0001",
                        "quantity", 2, "unit_price_minor", 6250L, "currency", "CAD"), // valid
                rowView("order_id", "ORD-2", "line_no", 0, "sku_id", "SKU-0002",
                        "quantity", 0, "unit_price_minor", 100L, "currency", "CAD"), // DQ-COMPLETE (qty 0)
                rowView("order_id", "ORD-99", "line_no", 0, "sku_id", "SKU-0003",
                        "quantity", 1, "unit_price_minor", 100L, "currency", "CAD"), // DQ-REFERENTIAL (orphan)
                rowView("order_id", "ORD-1", "line_no", 0, "sku_id", "SKU-0001",
                        "quantity", 2, "unit_price_minor", 6250L, "currency", "CAD"), // DQ-UNIQUE (dup)
                rowView("order_id", "ORD-4", "line_no", 0, "sku_id", "SKU-0004",
                        "quantity", 3, "unit_price_minor", 500L, "currency", "CAD")); // valid

        List<DqResult> results = DqRunner.run("fact_orders", orders, List.of(
                new OrderCompletenessRule(),
                new UniqueKeysRule("fact_orders", List.of("order_id"), List.of("order_id")),
                new ReferentialKeysRule("fact_orders", "store_id", "dim_store", dimStoreKeys, List.of("order_id"))));
        results.addAll(DqRunner.run("fact_order_lines", lines, List.of(
                new LineCompletenessRule(),
                new UniqueKeysRule("fact_order_lines", List.of("order_id", "line_no"), List.of("order_id", "line_no")),
                new ReferentialKeysRule("fact_order_lines", "order_id", "fact_orders", factOrderKeys,
                        List.of("order_id", "line_no")))));

        boolean allCaught = true;
        for (DqResult result : results) {
            boolean caught = result.violations() > 0;
            allCaught &= caught;
            System.out.println("DQ-SELFTEST rule=" + result.ruleId() + " table=" + result.tableName()
                    + " checked=" + result.checked() + " violations=" + result.violations()
                    + " caught=" + caught);
        }
        System.out.flush();
        if (!allCaught) {
            LOG.error("DQ-SELFTEST-FAILED at least one rule missed its fixture violation");
            return 2;
        }
        System.out.println("DQ-SELFTEST-OK all " + results.size() + " rule checks caught their fixture violations");
        return 0;
    }
}

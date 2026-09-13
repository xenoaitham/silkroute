package com.mapleretail.silkroute.batch.dq.rules;

import java.util.ArrayList;
import java.util.List;

import com.mapleretail.silkroute.batch.dq.DqRule;
import com.mapleretail.silkroute.batch.dq.DqRunner;
import com.mapleretail.silkroute.batch.dq.RowView;
import com.mapleretail.silkroute.batch.dq.Violation;

/**
 * DQ-COMPLETE for order lines: sku_id, unit_price_minor, currency non-null
 * AND quantity > 0 (the brief folds the positive-quantity check into
 * completeness for lines).
 */
public final class LineCompletenessRule implements DqRule {

    public static final String RULE_ID = "DQ-COMPLETE";
    private static final List<String> REQUIRED = List.of("sku_id", "unit_price_minor", "currency");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String tableName() {
        return "fact_order_lines";
    }

    @Override
    public Violation check(RowView row) {
        List<String> problems = new ArrayList<>();
        for (String column : REQUIRED) {
            if (row.get(column) == null) {
                problems.add(column + " is null");
            }
        }
        Object quantity = row.get("quantity");
        if (quantity == null) {
            problems.add("quantity is null");
        } else if (quantity instanceof Number n && n.longValue() <= 0) {
            problems.add("quantity must be > 0, got " + quantity);
        }
        if (problems.isEmpty()) {
            return null;
        }
        String key = row.get("order_id") + "|" + row.get("line_no");
        return new Violation(RULE_ID, tableName(), key, String.join("; ", problems), DqRunner.rowJson(row));
    }
}

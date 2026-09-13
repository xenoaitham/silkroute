package com.mapleretail.silkroute.batch.dq.rules;

import java.util.List;

import com.mapleretail.silkroute.batch.dq.DqRule;
import com.mapleretail.silkroute.batch.dq.DqRunner;
import com.mapleretail.silkroute.batch.dq.RowView;
import com.mapleretail.silkroute.batch.dq.Violation;

/**
 * DQ-COMPLETE for orders (MASTER_PROMPT §3: completeness): order_id, store_id,
 * region, total_amount_minor, currency must all be non-null.
 */
public final class OrderCompletenessRule implements DqRule {

    public static final String RULE_ID = "DQ-COMPLETE";
    private static final List<String> REQUIRED = List.of("order_id", "store_id", "region", "total_amount_minor", "currency");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String tableName() {
        return "fact_orders";
    }

    @Override
    public Violation check(RowView row) {
        List<String> missing = REQUIRED.stream().filter(c -> row.get(c) == null).toList();
        if (missing.isEmpty()) {
            return null;
        }
        String key = String.valueOf(row.get("order_id"));
        return new Violation(RULE_ID, tableName(), key, "missing required field(s): " + missing, DqRunner.rowJson(row));
    }
}

package com.mapleretail.silkroute.batch.dq.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mapleretail.silkroute.batch.dq.DqRule;
import com.mapleretail.silkroute.batch.dq.DqRunner;
import com.mapleretail.silkroute.batch.dq.RowView;
import com.mapleretail.silkroute.batch.dq.Violation;

/**
 * DQ-REFERENTIAL (the component spec): the row's foreign key must exist in the
 * referenced key set (e.g. every fact_order_lines.order_id exists in
 * fact_orders; every fact_orders.store_id exists in dim_store). Constructed
 * with the referenced key set - per-run instance.
 */
public final class ReferentialKeysRule implements DqRule {

    public static final String RULE_ID = "DQ-REFERENTIAL";

    private final String tableName;
    private final String keyColumn;
    private final String referencedTable;
    private final Set<Object> knownKeys;
    private final List<String> pkColumns;

    public ReferentialKeysRule(String tableName, String keyColumn, String referencedTable, Set<Object> knownKeys,
            List<String> pkColumns) {
        this.tableName = tableName;
        this.keyColumn = keyColumn;
        this.referencedTable = referencedTable;
        this.knownKeys = new HashSet<>(knownKeys);
        this.pkColumns = List.copyOf(pkColumns);
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public Violation check(RowView row) {
        Object key = row.get(keyColumn);
        if (key == null || !knownKeys.contains(key)) {
            String rowKey = String.join("|", pkColumns.stream()
                    .map(pk -> row.get(pk) == null ? "null" : String.valueOf(row.get(pk))).toList());
            return new Violation(RULE_ID, tableName, rowKey,
                    keyColumn + "=" + (key == null ? "null" : key) + " has no matching row in " + referencedTable,
                    DqRunner.rowJson(row));
        }
        return null;
    }
}

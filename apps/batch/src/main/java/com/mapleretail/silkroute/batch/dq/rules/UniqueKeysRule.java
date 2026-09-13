package com.mapleretail.silkroute.batch.dq.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mapleretail.silkroute.batch.dq.DqRule;
import com.mapleretail.silkroute.batch.dq.DqRunner;
import com.mapleretail.silkroute.batch.dq.RowView;
import com.mapleretail.silkroute.batch.dq.Violation;

/**
 * DQ-UNIQUE (MASTER_PROMPT §3): the key columns must be unique within the
 * table pass. Stateful per run: feed rows once, in order; the SECOND and any
 * later occurrence of a key is a violation (the first stays in gold).
 * Instances are NOT thread-safe and NOT reusable across runs.
 */
public final class UniqueKeysRule implements DqRule {

    public static final String RULE_ID = "DQ-UNIQUE";

    private final String tableName;
    private final List<String> keyColumns;
    private final List<String> pkColumns;
    private final Set<List<Object>> seen = new HashSet<>();

    public UniqueKeysRule(String tableName, List<String> keyColumns, List<String> pkColumns) {
        this.tableName = tableName;
        this.keyColumns = List.copyOf(keyColumns);
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
        List<Object> key = keyColumns.stream().map(row::get).toList();
        if (!seen.add(key)) {
            String rowKey = joinKey(pkColumns.stream().map(row::get).toList());
            return new Violation(RULE_ID, tableName, rowKey,
                    "duplicate key " + keyColumns + " = " + key + " (first occurrence kept, later occurrences quarantined)",
                    DqRunner.rowJson(row));
        }
        return null;
    }

    private String joinKey(List<Object> parts) {
        return String.join("|", parts.stream().map(p -> p == null ? "null" : String.valueOf(p)).toList());
    }
}

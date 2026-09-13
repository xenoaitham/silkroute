package com.mapleretail.silkroute.batch.dq;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The rule runner used by BOTH the Spark pipeline (driver-side row adapter)
 * and the selftest: feed rows in, get per-rule results + violations.
 * A check that cannot fail is not a check — the selftest proves every rule
 * catches its own fixture violation.
 */
public final class DqRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DqRunner() {
    }

    public static List<DqResult> run(String tableName, List<RowView> rows, List<DqRule> rules) {
        List<DqResult> results = new ArrayList<>();
        for (DqRule rule : rules) {
            List<Violation> violations = new ArrayList<>();
            for (RowView row : rows) {
                Violation violation = rule.check(row);
                if (violation != null) {
                    violations.add(violation);
                }
            }
            results.add(new DqResult(rule.ruleId(), rule.tableName(), rows.size(), violations.size(), violations));
        }
        return results;
    }

    /** Stable JSON rendering of a row for the quarantine table (violating_row_json). */
    public static String rowJson(RowView row) {
        try {
            Map<String, Object> ordered = new TreeMap<>();
            for (String column : row.columns()) {
                ordered.put(column, row.get(column));
            }
            return MAPPER.writeValueAsString(ordered);
        } catch (Exception e) {
            return "{\"error\":\"unserializable row: " + e.toString().replace("\"", "'") + "\"}";
        }
    }
}

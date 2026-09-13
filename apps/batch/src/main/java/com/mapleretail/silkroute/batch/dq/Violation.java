package com.mapleretail.silkroute.batch.dq;

/** One quarantined violation (a row failed a rule). */
public final class Violation {

    private final String ruleId;
    private final String tableName;
    private final String rowKey;
    private final String reason;
    private final String rowJson;

    public Violation(String ruleId, String tableName, String rowKey, String reason, String rowJson) {
        this.ruleId = ruleId;
        this.tableName = tableName;
        this.rowKey = rowKey;
        this.reason = reason;
        this.rowJson = rowJson;
    }

    public String ruleId() {
        return ruleId;
    }

    public String tableName() {
        return tableName;
    }

    public String rowKey() {
        return rowKey;
    }

    public String reason() {
        return reason;
    }

    public String rowJson() {
        return rowJson;
    }
}

package com.mapleretail.silkroute.batch.dq;

import java.util.List;

/** Result of running one rule over one table for one run. */
public record DqResult(String ruleId, String tableName, long checked, long violations, List<Violation> violationRows) {

    public String status() {
        return violations == 0 ? "PASS" : "FAIL";
    }
}

package com.mapleretail.silkroute.batch.dq;

/**
 * A DQ predicate over one row (pure Java, no Spark). Instances of stateful
 * rules (uniqueness) are PER-RUN: create fresh instances for every pass.
 * check() returns a Violation, or null when the row passes.
 */
public interface DqRule {

    String ruleId();

    String tableName();

    Violation check(RowView row);
}

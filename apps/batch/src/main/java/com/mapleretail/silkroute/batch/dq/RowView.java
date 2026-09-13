package com.mapleretail.silkroute.batch.dq;

import java.util.Set;

/**
 * CRITICAL DESIGN CONSTRAINT (build brief): the DQ rule PREDICATES operate on
 * this minimal row-view interface so they are pure-Java — unit-testable
 * WITHOUT Spark and reusable by `selftest`. The Spark layer only adapts rows
 * to this interface.
 */
public interface RowView {

    /** Column value, null when absent/null. */
    Object get(String column);

    /** All column names this row carries. */
    Set<String> columns();
}

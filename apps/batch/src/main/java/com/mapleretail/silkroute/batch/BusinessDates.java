package com.mapleretail.silkroute.batch;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;

/**
 * Business-date helpers - C5 timezone-explicit: the T+1 batch window is defined
 * in Asia/Singapore (the ops hub), the bronze dt= partitions are UTC dates of
 * the source-commit timestamp (ADR-0006). Both facts are pinned in code, never
 * left to the JVM default zone.
 */
public final class BusinessDates {

    public static final ZoneId BATCH_WINDOW_ZONE = ZoneId.of("Asia/Singapore");

    private BusinessDates() {
    }

    /** Default business date = yesterday in Asia/Singapore, computed from the given clock. */
    public static String yesterdayAsiaSingapore(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), BATCH_WINDOW_ZONE).minusDays(1).toString();
    }

    /** dt= partition digits (yyyyMMdd) for a yyyy-MM-dd business date. */
    public static String dtDigits(String businessDate) {
        return businessDate.replace("-", "");
    }

    /** Strict parse - a malformed --business-date arg must fail loudly, not misroute a partition. */
    public static LocalDate parse(String businessDate) {
        return LocalDate.parse(businessDate);
    }
}

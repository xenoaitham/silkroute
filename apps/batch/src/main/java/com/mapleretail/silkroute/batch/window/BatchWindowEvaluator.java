package com.mapleretail.silkroute.batch.window;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * C4/C5 window evaluator (ADR-0006 decision 4): the T+1 batch must complete by
 * 06:00 Asia/Singapore. ZoneId is HARDCODED (not a knob — the window is a
 * business fact), the java.time.Clock is INJECTABLE so the negative case
 * (a clock past 06:00 must evaluate withinWindow=false) is unit-testable.
 *
 * Honest labeling: in sim the job runs on demand, so withinWindow reports the
 * ACTUAL run time vs the 06:00 boundary; the unit tests prove the window logic
 * itself.
 */
public final class BatchWindowEvaluator {

    public static final ZoneId WINDOW_ZONE = ZoneId.of("Asia/Singapore");
    public static final LocalTime WINDOW_END_SGT = LocalTime.of(6, 0);

    /** completedAtSGT pattern + zone label, per the contract line shape. */
    private static final DateTimeFormatter SGT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public record Result(String businessDate, String completedAtSgt, boolean withinWindow) {
    }

    private BatchWindowEvaluator() {
    }

    public static Result evaluate(Clock clock, String businessDate) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(WINDOW_ZONE));
        // "complete by 06:00" — 06:00:00.000 itself is the boundary, still within
        boolean within = !now.toLocalTime().isAfter(WINDOW_END_SGT);
        String completedAt = now.format(SGT_FORMAT) + " Asia/Singapore";
        return new Result(businessDate, completedAt, within);
    }

    /** The exact contract line. */
    public static String contractLine(Result result) {
        return "BATCH-WINDOW businessDate=" + result.businessDate()
                + " completedAtSGT=" + result.completedAtSgt()
                + " windowEndSGT=06:00"
                + " withinWindow=" + result.withinWindow();
    }

    /** Convenience for callers that already have a ZonedDateTime (tests). */
    public static boolean withinWindow(LocalDateTime sgtTime) {
        return !sgtTime.toLocalTime().isAfter(WINDOW_END_SGT);
    }
}

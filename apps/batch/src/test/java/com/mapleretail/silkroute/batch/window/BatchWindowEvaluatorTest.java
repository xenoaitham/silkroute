package com.mapleretail.silkroute.batch.window;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchWindowEvaluatorTest {

    private static Clock sgt(int hour, int minute) {
        // 2026-09-14 is a Monday; the SGT wall time is fixed via its UTC offset (+08:00)
        return Clock.fixed(Instant.parse("2026-09-14T00:00:00Z").plusSeconds((long) (hour - 8) * 3600 + (long) minute * 60),
                ZoneId.of("Asia/Singapore"));
    }

    @Test
    void beforeWindowEndIsWithin() {
        assertTrue(BatchWindowEvaluator.withinWindow(LocalDateTime.of(2026, 9, 14, 5, 59, 0)));
        assertTrue(BatchWindowEvaluator.withinWindow(LocalDateTime.of(2026, 9, 14, 0, 0, 0)));
    }

    @Test
    void boundarySixOclockSharpIsStillWithin() {
        assertTrue(BatchWindowEvaluator.withinWindow(LocalDateTime.of(2026, 9, 14, 6, 0, 0)));
    }

    @Test
    void pastWindowEndIsOutside_NEGATIVE_CASE() {
        // THE negative case: a clock past 06:00 must evaluate withinWindow=false
        assertFalse(BatchWindowEvaluator.withinWindow(LocalDateTime.of(2026, 9, 14, 6, 0, 1)));
        assertFalse(BatchWindowEvaluator.withinWindow(LocalDateTime.of(2026, 9, 14, 23, 59, 0)));
    }

    @Test
    void evaluateUsesTheInjectedClockAndTheHardcodedZone() {
        // 2026-09-14T05:30 SGT
        BatchWindowEvaluator.Result early = BatchWindowEvaluator.evaluate(sgt(5, 30), "2026-09-13");
        assertEquals("2026-09-13", early.businessDate());
        assertEquals("2026-09-14T05:30:00 Asia/Singapore", early.completedAtSgt());
        assertTrue(early.withinWindow());

        // 2026-09-14T06:05 SGT - just past the boundary
        BatchWindowEvaluator.Result late = BatchWindowEvaluator.evaluate(sgt(6, 5), "2026-09-13");
        assertEquals("2026-09-14T06:05:00 Asia/Singapore", late.completedAtSgt());
        assertFalse(late.withinWindow());
    }

    @Test
    void contractLineHasTheExactShape() {
        BatchWindowEvaluator.Result result = BatchWindowEvaluator.evaluate(sgt(6, 5), "2026-09-13");
        assertEquals("BATCH-WINDOW businessDate=2026-09-13 completedAtSGT=2026-09-14T06:05:00 Asia/Singapore windowEndSGT=06:00 withinWindow=false",
                BatchWindowEvaluator.contractLine(result));
    }
}

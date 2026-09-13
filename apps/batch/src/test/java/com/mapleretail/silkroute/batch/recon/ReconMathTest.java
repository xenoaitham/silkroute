package com.mapleretail.silkroute.batch.recon;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconMathTest {

    @Test
    void perfectRunMatches100Percent() {
        ReconMath.Report report = ReconMath.compute("run-1", "2026-09-13",
                12, 12, 24, 24,
                Map.of("CAD", 25000L, "SGD", 15000L, "CNY", 8800L),
                Map.of("CAD", 25000L, "SGD", 15000L, "CNY", 8800L),
                "2026-09-13T12:00:00Z");
        assertTrue(report.orders().match());
        assertTrue(report.lines().match());
        assertTrue(report.allMatch());
        assertEquals(3, report.totalsByCurrency().size());
    }

    @Test
    void countMismatchGoesRed() {
        ReconMath.Report report = ReconMath.compute("run-2", "2026-09-13",
                12, 11, 24, 24,
                Map.of("CAD", 25000L), Map.of("CAD", 25000L), "2026-09-13T12:00:00Z");
        assertFalse(report.orders().match());
        assertFalse(report.allMatch());
    }

    @Test
    void moneyIsComparedPerCurrencyNeverSummedAcross() {
        // same grand total across currencies, wrong distribution per currency: must NOT match
        ReconMath.Report report = ReconMath.compute("run-3", "2026-09-13",
                2, 2, 2, 2,
                Map.of("CAD", 10000L, "SGD", 5000L),
                Map.of("CAD", 5000L, "SGD", 10000L), "2026-09-13T12:00:00Z");
        assertFalse(report.allMatch());
        assertEquals(2, report.totalsByCurrency().size());
        assertTrue(report.totalsByCurrency().stream().anyMatch(t -> !t.match()));
    }

    @Test
    void currencyPresentOnlyOnOneSideIsRed() {
        ReconMath.Report report = ReconMath.compute("run-4", "2026-09-13",
                1, 1, 1, 1,
                Map.of("CNY", 8800L), Map.of(), "2026-09-13T12:00:00Z");
        assertFalse(report.allMatch());
        assertEquals(1, report.totalsByCurrency().size());
        assertEquals(8800L, report.totalsByCurrency().get(0).sourceMinor());
        assertEquals(0L, report.totalsByCurrency().get(0).goldMinor());
    }

    @Test
    void fullReportIncludesTheLineTotalsArray() {
        ReconMath.FullReport report = ReconMath.computeFull("run-5", "2026-09-13",
                1, 1, 2, 2,
                Map.of("CAD", 12500L), Map.of("CAD", 12500L),
                Map.of("CAD", 12500L), Map.of("CAD", 6250L), // line sum mismatch
                "2026-09-13T12:00:00Z");
        assertTrue(report.totalsByCurrency().stream().allMatch(ReconMath.CurrencyTotal::match));
        assertFalse(report.lineTotalsByCurrency().stream().allMatch(ReconMath.CurrencyTotal::match));
        assertFalse(report.allMatch());
    }
}

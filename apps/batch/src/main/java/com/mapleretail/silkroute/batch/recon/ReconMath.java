package com.mapleretail.silkroute.batch.recon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure reconciliation math (the AC: source vs gold must be 100%). No Spark,
 * no IO - unit-testable on in-memory rows. Currency totals are keyed by the
 * ISO code (C5): money is compared per currency, never across.
 */
public final class ReconMath {

    private ReconMath() {
    }

    public record CurrencyTotal(String currency, long sourceMinor, long goldMinor, boolean match) {
    }

    public record Side(long source, long gold, boolean match) {
    }

    public record Report(String runId, String businessDate, Side orders, Side lines,
            List<CurrencyTotal> totalsByCurrency, boolean allMatch, String generatedAt) {
    }

    /**
     * The full serialized report: the pinned shape {runId, businessDate, orders,
     * lines, totalsByCurrency, allMatch, generatedAt} PLUS the additive
     * lineTotalsByCurrency array (SUM(line_total_minor) by currency) so both
     * money aggregates from the brief are reconciled and visible.
     */
    public record FullReport(String runId, String businessDate, Side orders, Side lines,
            List<CurrencyTotal> totalsByCurrency, List<CurrencyTotal> lineTotalsByCurrency,
            boolean allMatch, String generatedAt) {
    }

    public static Report compute(String runId, String businessDate,
            long ordersSource, long ordersGold, long linesSource, long linesGold,
            Map<String, Long> sourceTotalsByCurrency, Map<String, Long> goldTotalsByCurrency,
            String generatedAtUtc) {

        List<CurrencyTotal> totals = currencyTotals(sourceTotalsByCurrency, goldTotalsByCurrency);
        Side orders = new Side(ordersSource, ordersGold, ordersSource == ordersGold);
        Side lines = new Side(linesSource, linesGold, linesSource == linesGold);
        boolean allMatch = orders.match() && lines.match() && totals.stream().allMatch(CurrencyTotal::match);
        return new Report(runId, businessDate, orders, lines, totals, allMatch, generatedAtUtc);
    }

    public static FullReport computeFull(String runId, String businessDate,
            long ordersSource, long ordersGold, long linesSource, long linesGold,
            Map<String, Long> sourceOrderTotals, Map<String, Long> goldOrderTotals,
            Map<String, Long> sourceLineTotals, Map<String, Long> goldLineTotals,
            String generatedAtUtc) {
        Report base = compute(runId, businessDate, ordersSource, ordersGold, linesSource, linesGold,
                sourceOrderTotals, goldOrderTotals, generatedAtUtc);
        List<CurrencyTotal> lineTotals = currencyTotals(sourceLineTotals, goldLineTotals);
        boolean allMatch = base.allMatch() && lineTotals.stream().allMatch(CurrencyTotal::match);
        return new FullReport(base.runId(), base.businessDate(), base.orders(), base.lines(),
                base.totalsByCurrency(), lineTotals, allMatch, base.generatedAt());
    }

    /** Per-currency comparison, union of both sides' currencies (never summed across currencies, C5). */
    public static List<CurrencyTotal> currencyTotals(Map<String, Long> sourceTotals, Map<String, Long> goldTotals) {
        List<CurrencyTotal> totals = new ArrayList<>();
        for (String currency : new TreeMap<>(sourceTotals).keySet()) {
            totals.add(total(currency, sourceTotals, goldTotals));
        }
        for (String currency : new TreeMap<>(goldTotals).keySet()) {
            if (!sourceTotals.containsKey(currency)) {
                totals.add(total(currency, sourceTotals, goldTotals));
            }
        }
        return totals;
    }

    private static CurrencyTotal total(String currency, Map<String, Long> sourceTotals, Map<String, Long> goldTotals) {
        long sourceMinor = sourceTotals.getOrDefault(currency, 0L);
        long goldMinor = goldTotals.getOrDefault(currency, 0L);
        return new CurrencyTotal(currency, sourceMinor, goldMinor, sourceMinor == goldMinor);
    }
}

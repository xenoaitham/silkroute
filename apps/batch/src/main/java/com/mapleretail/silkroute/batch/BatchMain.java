package com.mapleretail.silkroute.batch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mapleretail.silkroute.batch.dq.DqResult;
import com.mapleretail.silkroute.batch.dq.DqRunner;
import com.mapleretail.silkroute.batch.dq.JdbcDqWriter;
import com.mapleretail.silkroute.batch.dq.MapRowView;
import com.mapleretail.silkroute.batch.dq.RowView;
import com.mapleretail.silkroute.batch.dq.rules.LineCompletenessRule;
import com.mapleretail.silkroute.batch.dq.rules.OrderCompletenessRule;
import com.mapleretail.silkroute.batch.dq.rules.ReferentialKeysRule;
import com.mapleretail.silkroute.batch.dq.rules.UniqueKeysRule;
import com.mapleretail.silkroute.batch.metrics.MetricsEmitter;
import com.mapleretail.silkroute.batch.pipeline.GoldBuilder;
import com.mapleretail.silkroute.batch.pipeline.ReconRunner;
import com.mapleretail.silkroute.batch.pipeline.SilverPipeline;
import com.mapleretail.silkroute.batch.recon.ReconMath;
import com.mapleretail.silkroute.batch.selftest.DqSelftest;
import com.mapleretail.silkroute.batch.window.BatchWindowEvaluator;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.concat_ws;
import static org.apache.spark.sql.functions.lit;

/**
 * CLI: full [--business-date YYYY-MM-DD] | recon-only [--business-date ...] | selftest.
 *
 * Exit codes: 0 = success (recon allMatch for full/recon-only; every rule caught
 * for selftest); 2 = recon mismatch (full/recon-only) or selftest failure; 1 =
 * any other error (loud stack trace).
 */
public final class BatchMain {

    private static final Logger LOG = LoggerFactory.getLogger(BatchMain.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) {
        long startNanos = System.nanoTime();
        String mode = args.length > 0 ? args[0] : "";
        String businessDateArg = null;
        for (int i = 1; i < args.length - 1; i++) {
            if ("--business-date".equals(args[i])) {
                businessDateArg = args[i + 1];
            }
        }
        JobConfig cfg = new JobConfig();
        try {
            int exit;
            switch (mode) {
                case "full" -> exit = runFull(cfg, businessDateArg, startNanos);
                case "recon-only" -> exit = runReconOnly(cfg, businessDateArg, startNanos);
                case "selftest" -> exit = DqSelftest.run();
                default -> {
                    System.err.println("USAGE: java -jar batch-1.0.0-SNAPSHOT.jar full [--business-date YYYY-MM-DD] | recon-only [--business-date YYYY-MM-DD] | selftest");
                    exit = 2;
                }
            }
            System.exit(exit);
        } catch (Exception e) {
            LOG.error("BATCH-FAILED mode={} error={}", mode, e.toString(), e);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ full

    private static int runFull(JobConfig cfg, String businessDateArg, long startNanos) {
        String businessDate = resolveBusinessDate(businessDateArg);
        String dt = BusinessDates.dtDigits(businessDate);
        String runId = newRunId();
        LOG.info("BATCH-FULL-START runId={} businessDate={} dt={} bronze={} master={}",
                runId, businessDate, dt, cfg.bronzeReadPath(dt), cfg.sparkMaster);

        ReconMath.FullReport report;
        try (SparkSession spark = SparkSessions.create(cfg)) {
            // 1. bronze -> silver (typed, deduped, lineage) + silver writes
            SilverPipeline.SilverData silver = SilverPipeline.build(spark, cfg, dt);

            // 2. DQ on silver BEFORE gold: pure predicates, driver-side row adapter (sim volumes)
            List<RowView> orderViews = toViews(silver.orders());
            List<RowView> lineViews = toViews(silver.lines());
            Set<Object> dimStoreKeys = distinctKeys(silver.orders().filter(col("store_id").isNotNull()), "store_id");

            try (JdbcDqWriter dq = new JdbcDqWriter(cfg.dqJdbcUrl, cfg.dqDbUser, cfg.dqDbPassword)) {
                List<DqResult> orderResults = DqRunner.run("fact_orders", orderViews, List.of(
                        new OrderCompletenessRule(),
                        new UniqueKeysRule("fact_orders", List.of("order_id"), List.of("order_id")),
                        new ReferentialKeysRule("fact_orders", "store_id", "dim_store", dimStoreKeys, List.of("order_id"))));

                Set<String> quarantinedOrderIds = quarantinedKeys(orderResults);
                Set<Object> factOrderKeys = new HashSet<>(orderViews.stream()
                        .map(r -> (String) r.get("order_id"))
                        .filter(id -> id != null && !quarantinedOrderIds.contains(id))
                        .toList());

                List<DqResult> lineResults = DqRunner.run("fact_order_lines", lineViews, List.of(
                        new LineCompletenessRule(),
                        new UniqueKeysRule("fact_order_lines", List.of("order_id", "line_no"), List.of("order_id", "line_no")),
                        new ReferentialKeysRule("fact_order_lines", "order_id", "fact_orders", factOrderKeys,
                                List.of("order_id", "line_no"))));

                java.time.Instant nowUtc = java.time.Instant.now();
                List<DqResult> allResults = new ArrayList<>(orderResults);
                allResults.addAll(lineResults);
                List<com.mapleretail.silkroute.batch.dq.Violation> allViolations = allResults.stream()
                        .flatMap(r -> r.violationRows().stream()).toList();
                dq.writeQuarantine(runId, allViolations, nowUtc);
                dq.writeResults(runId, allResults, nowUtc);

                if (!allViolations.isEmpty()) {
                    LOG.warn("DQ-QUARANTINE-NONEMPTY run={} violations={} - quarantined rows are EXCLUDED from gold; "
                            + "the reconciliation is expected to go RED while violations exist (by design)",
                            runId, allViolations.size());
                }

                // 3. gold from DQ-passed silver
                Set<String> quarantinedLineKeys = quarantinedKeys(lineResults);
                Dataset<Row> passOrders = silver.orders().filter(notIn(col("order_id"), quarantinedOrderIds));
                Dataset<Row> passLines = silver.lines()
                        .filter(notIn(col("order_id"), quarantinedOrderIds))
                        .filter(notIn(concat_ws("|", col("order_id"), col("line_no")), quarantinedLineKeys));

                GoldBuilder.build(spark, cfg, businessDate, dt, passOrders, passLines);
            }

            // 4. reconciliation: source (MySQL) vs gold (re-read from the bucket)
            report = ReconRunner.run(spark, cfg, runId, businessDate, dt);
        }
        return finishRun(cfg, runId, report, startNanos);
    }

    // ------------------------------------------------------------- recon-only

    private static int runReconOnly(JobConfig cfg, String businessDateArg, long startNanos) {
        String businessDate = resolveBusinessDate(businessDateArg);
        String dt = BusinessDates.dtDigits(businessDate);
        String runId = newRunId();
        LOG.info("BATCH-RECON-ONLY-START runId={} businessDate={} dt={} (negative-control mode: no rebuild)", runId, businessDate, dt);
        ReconMath.FullReport report;
        try (SparkSession spark = SparkSessions.create(cfg)) {
            report = ReconRunner.run(spark, cfg, runId, businessDate, dt);
        }
        return finishRun(cfg, runId, report, startNanos);
    }

    // ---------------------------------------------------------------- shared

    private static int finishRun(JobConfig cfg, String runId, ReconMath.FullReport report, long startNanos) {
        writeReport(cfg.reconReportPath, report);
        try (JdbcDqWriter dq = new JdbcDqWriter(cfg.dqJdbcUrl, cfg.dqDbUser, cfg.dqDbPassword)) {
            dq.writeReconReport(runId, report, JSON.valueToTree(report.totalsByCurrency()).toString(),
                    java.time.Instant.now());
        }
        long wallSeconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        MetricsEmitter.emit("batch_completion", wallSeconds, "batch", cfg.metricsFile);

        BatchWindowEvaluator.Result window = BatchWindowEvaluator.evaluate(Clock.systemUTC(), report.businessDate());
        System.out.println(BatchWindowEvaluator.contractLine(window));
        System.out.flush();
        LOG.info("BATCH-DONE runId={} allMatch={} reportPath={}", runId, report.allMatch(), cfg.reconReportPath);
        return report.allMatch() ? 0 : 2;
    }

    private static String resolveBusinessDate(String arg) {
        if (arg != null) {
            BusinessDates.parse(arg); // validates loudly
            return arg;
        }
        return BusinessDates.yesterdayAsiaSingapore(Clock.systemUTC());
    }

    private static void writeReport(String path, ReconMath.FullReport report) {
        try {
            JSON.writeValue(Path.of(path).toFile(), report);
            LOG.info("RECON-REPORT-WRITTEN path={} allMatch={}", path, report.allMatch());
        } catch (Exception e) {
            throw new IllegalStateException("RECON-REPORT-WRITE-FAILED path=" + path + ": " + e.getMessage(), e);
        }
    }

    private static String newRunId() {
        return "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    static List<RowView> toViews(Dataset<Row> df) {
        List<Row> rows = df.collectAsList();
        List<RowView> views = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Map<String, Object> map = new TreeMap<>();
            String[] columns = row.schema().fieldNames();
            for (int i = 0; i < columns.length; i++) {
                map.put(columns[i], row.isNullAt(i) ? null : row.get(i));
            }
            views.add(MapRowView.of(map));
        }
        return views;
    }

    private static Set<Object> distinctKeys(Dataset<Row> df, String column) {
        Set<Object> keys = new HashSet<>();
        for (Row row : df.select(column).distinct().collectAsList()) {
            keys.add(row.isNullAt(0) ? null : row.get(0));
        }
        return keys;
    }

    private static Set<String> quarantinedKeys(List<DqResult> results) {
        Set<String> keys = new HashSet<>();
        for (DqResult result : results) {
            for (var violation : result.violationRows()) {
                keys.add(violation.rowKey());
            }
        }
        return keys;
    }

    private static org.apache.spark.sql.Column notIn(org.apache.spark.sql.Column column, Set<String> keys) {
        if (keys.isEmpty()) {
            return lit(true);
        }
        return org.apache.spark.sql.functions.not(column.isin(keys.toArray(new String[0])));
    }
}

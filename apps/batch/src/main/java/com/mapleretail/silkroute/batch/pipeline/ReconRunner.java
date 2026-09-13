package com.mapleretail.silkroute.batch.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapleretail.silkroute.batch.JobConfig;
import com.mapleretail.silkroute.batch.recon.ReconMath;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.sum;

/**
 * Reconciliation (THE acceptance criterion: source vs gold, must be 100%).
 *
 * source = the OMS MySQL (silkroute_oms) read via Spark JDBC — the real system
 * of record; rows scoped to the business date by the UTC calendar date of
 * oms_order.ingested_at (the JDBC URL pins the session to UTC, C5).
 * target = the gold Parquet JUST built, re-read from the gold bucket (a real
 * round trip, not the in-memory frames).
 *
 * Aggregates: orders count, lines count, SUM(total_amount_minor) by currency
 * AND SUM(line_total_minor) by currency — all compared per currency (C5:
 * money is never summed across currencies).
 *
 * Report JSON keeps the pinned shape {runId, businessDate, orders, lines,
 * totalsByCurrency, allMatch, generatedAt} where totalsByCurrency carries the
 * ORDER money; the line money sums are carried in the ADDITIVE
 * lineTotalsByCurrency array (same shape) so both SUMs from the brief are
 * actually reconciled.
 *
 * recon-only reuses this with the gold build skipped: the negative-control
 * instrument (drop a gold row -> recon goes red).
 */
public final class ReconRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReconRunner.class);

    private ReconRunner() {
    }

    public static ReconMath.FullReport run(SparkSession spark, JobConfig cfg, String runId, String businessDate, String dt) {
        // ---- source: OMS MySQL via Spark JDBC ----
        Dataset<Row> srcOrders = spark.read().format("jdbc")
                .option("url", cfg.reconJdbcUrl)
                .option("dbtable", "silkroute_oms.oms_order")
                .option("user", cfg.reconDbUser)
                .option("password", cfg.reconDbPassword)
                .option("driver", "com.mysql.cj.jdbc.Driver")
                .load()
                .filter(coalesce(col("ingested_at").cast("date"), lit("1900-01-01").cast("date"))
                        .equalTo(lit(businessDate)));

        Dataset<Row> srcLines = spark.read().format("jdbc")
                .option("url", cfg.reconJdbcUrl)
                .option("dbtable", "silkroute_oms.oms_order_line")
                .option("user", cfg.reconDbUser)
                .option("password", cfg.reconDbPassword)
                .option("driver", "com.mysql.cj.jdbc.Driver")
                .load()
                .join(srcOrders.select(col("order_id").as("src_order_id")),
                        col("order_id").equalTo(col("src_order_id")))
                .drop("src_order_id");

        long ordersSource = srcOrders.count();
        long linesSource = srcLines.count();
        Map<String, Long> sourceOrderTotals = totals(srcOrders, "total_amount_minor");
        Map<String, Long> sourceLineTotals = totals(srcLines, "line_total_minor");

        // ---- target: the gold parquet, re-read from the bucket ----
        Dataset<Row> goldOrders = spark.read().parquet(cfg.goldPath("fact_orders"))
                .filter(col("dt").equalTo(dt));
        Dataset<Row> goldLines = spark.read().parquet(cfg.goldPath("fact_order_lines"))
                .filter(col("dt").equalTo(dt));

        long ordersGold = goldOrders.count();
        long linesGold = goldLines.count();
        Map<String, Long> goldOrderTotals = totals(goldOrders, "total_amount_minor");
        Map<String, Long> goldLineTotals = totals(goldLines, "line_total_minor");

        String generatedAt = java.time.Instant.now().toString().substring(0, 19) + "Z";
        ReconMath.FullReport report = ReconMath.computeFull(runId, businessDate,
                ordersSource, ordersGold, linesSource, linesGold,
                sourceOrderTotals, goldOrderTotals, sourceLineTotals, goldLineTotals, generatedAt);

        LOG.info("RECON-COMPARED run={} orders source={} gold={} match={} | lines source={} gold={} match={} | orderTotals={} lineTotals={}",
                runId, ordersSource, ordersGold, report.orders().match(),
                linesSource, linesGold, report.lines().match(), report.totalsByCurrency(), report.lineTotalsByCurrency());
        return report;
    }

    private static Map<String, Long> totals(Dataset<Row> df, String moneyColumn) {
        List<Row> rows = df.groupBy(coalesce(col("currency"), lit("__NULL__")).as("currency"))
                .agg(count(lit(1)).as("rows"), coalesce(sum(col(moneyColumn)), lit(0)).as("total_minor"))
                .collectAsList();
        Map<String, Long> byCurrency = new LinkedHashMap<>();
        for (Row row : rows) {
            byCurrency.put(row.getAs("currency"), ((Number) row.getAs("total_minor")).longValue());
        }
        return new TreeMap<>(byCurrency);
    }
}

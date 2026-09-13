package com.mapleretail.silkroute.batch.pipeline;

import java.util.List;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapleretail.silkroute.batch.JobConfig;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.concat;
import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.get_json_object;
import static org.apache.spark.sql.functions.input_file_name;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.row_number;

/**
 * Bronze -> silver (cleansed/conformed, ADR-0006 decision 3).
 *
 * - Reads ONLY the bronze JSONL of one dt= partition (UTC date of the
 *   source-commit ts); bronze itself is never touched.
 * - Keeps the FULL Debezium envelope semantics: ops c/r/u with a non-null
 *   after-image; latest-per-PK dedup keyed (table, PK, source.ts_ms) with a
 *   deterministic tiebreak (bronze_object DESC); op=d latest wins -> PK dropped.
 * - Types: money = BIGINT minor units + currency STRING (C5 — FloatType and
 *   DoubleType appear NOWHERE in this module); line_total_minor = integer
 *   multiplication with both operands cast to long BEFORE multiplying.
 * - Lineage: bronze_object = input_file_name().
 * - Silver writes are idempotent per business date: dynamic-partition
 *   overwrite replaces ONLY silver/<table>/dt=<date>/.
 */
public final class SilverPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(SilverPipeline.class);

    public record SilverData(Dataset<Row> orders, Dataset<Row> lines) {
    }

    private SilverPipeline() {
    }

    public static SilverData build(SparkSession spark, JobConfig cfg, String dt) {
        String bronzePath = cfg.bronzeReadPath(dt);
        Dataset<Row> raw;
        try {
            raw = spark.read().text(bronzePath);
        } catch (RuntimeException e) {
            // missing partition (AnalysisException "Path does not exist") or unreadable bucket: fail LOUD
            throw new IllegalStateException("BRONZE-READ-FAILED dt=" + dt + " path=" + bronzePath
                    + " — no bronze objects landed for this business date (check the date arg or run the day first); cause: "
                    + e.getMessage(), e);
        }

        Dataset<Row> envelopes = raw.select(
                        col("value").as("raw_value"),
                        get_json_object(col("value"), "$.op").as("op"),
                        get_json_object(col("value"), "$.source.table").as("table_name"),
                        get_json_object(col("value"), "$.source.ts_ms").cast("long").as("source_ts_ms"),
                        get_json_object(col("value"), "$.after").as("after_json"),
                        get_json_object(col("value"), "$.before").as("before_json"),
                        input_file_name().as("bronze_object"))
                .filter(col("op").isin("c", "r", "u", "d"))
                // c/r/u carry the after-image; d carries only the before-image — the PK
                // below falls back to it so a DELETE removes its own earlier insert
                .filter(col("after_json").isNotNull().or(col("before_json").isNotNull()))
                .filter(col("source_ts_ms").isNotNull())
                .filter(col("table_name").isNotNull());

        Dataset<Row> orders = latestPerPk(envelopes, "oms_order",
                        concat(coalesce(get_json_object(col("after_json"), "$.order_id"),
                                get_json_object(col("before_json"), "$.order_id"))))
                .select(
                        get_json_object(col("after_json"), "$.order_id").as("order_id"),
                        get_json_object(col("after_json"), "$.external_order_ref").as("external_order_ref"),
                        get_json_object(col("after_json"), "$.source_system").as("source_system"),
                        get_json_object(col("after_json"), "$.store_id").as("store_id"),
                        get_json_object(col("after_json"), "$.region").as("region"),
                        get_json_object(col("after_json"), "$.customer_ref").as("customer_ref"),
                        get_json_object(col("after_json"), "$.channel").as("channel"),
                        get_json_object(col("after_json"), "$.status").as("status"),
                        get_json_object(col("after_json"), "$.reservation_id").as("reservation_id"),
                        get_json_object(col("after_json"), "$.total_amount_minor").cast("long").as("total_amount_minor"),
                        get_json_object(col("after_json"), "$.currency").as("currency"),
                        get_json_object(col("after_json"), "$.correlation_id").as("correlation_id"),
                        col("source_ts_ms"),
                        col("bronze_object"))
                .withColumn("dt", lit(dt))
                .cache();

        Dataset<Row> lines = latestPerPk(envelopes, "oms_order_line",
                        concat(coalesce(get_json_object(col("after_json"), "$.order_id"),
                                        get_json_object(col("before_json"), "$.order_id")),
                                lit("|"),
                                coalesce(get_json_object(col("after_json"), "$.line_no"),
                                        get_json_object(col("before_json"), "$.line_no"))))
                .select(
                        get_json_object(col("after_json"), "$.order_id").as("order_id"),
                        get_json_object(col("after_json"), "$.line_no").cast("int").as("line_no"),
                        get_json_object(col("after_json"), "$.sku_id").as("sku_id"),
                        get_json_object(col("after_json"), "$.quantity").cast("int").as("quantity"),
                        get_json_object(col("after_json"), "$.unit_price_minor").cast("long").as("unit_price_minor"),
                        get_json_object(col("after_json"), "$.currency").as("currency"),
                        get_json_object(col("after_json"), "$.region").as("region"),
                        col("source_ts_ms"),
                        col("bronze_object"))
                // C5: integer minor-unit math — both operands cast to long BEFORE the multiply
                .withColumn("line_total_minor",
                        col("quantity").cast("long").multiply(col("unit_price_minor").cast("long")))
                .withColumn("dt", lit(dt))
                .cache();

        LOG.info("SILVER-BUILT dt={} orders={} lines={}", dt, orders.count(), lines.count());

        // idempotent per dt: dynamic-partition overwrite touches ONLY dt=<date>
        orders.write().mode("overwrite").partitionBy("dt").parquet(cfg.silverOrdersPath());
        lines.write().mode("overwrite").partitionBy("dt").parquet(cfg.silverOrderLinesPath());
        LOG.info("SILVER-WRITTEN ordersPath={} linesPath={}", cfg.silverOrdersPath(), cfg.silverOrderLinesPath());
        return new SilverData(orders, lines);
    }

    /**
     * Latest-per-PK with DELETE awareness: for each (table_name, pk) keep the row
     * with the highest source.ts_ms (ties broken deterministically by
     * bronze_object DESC) — and if the LATEST record for a PK is op=d, the PK is
     * DROPPED (the delete wins; its earlier insert must not reach gold). The PK
     * expression resolves from the after-image, falling back to the before-image
     * for deletes (which carry no after).
     */
    private static Dataset<Row> latestPerPk(Dataset<Row> envelopes, String tableName, org.apache.spark.sql.Column pkExpr) {
        return envelopes.filter(col("table_name").equalTo(tableName))
                .withColumn("pk", pkExpr)
                .withColumn("rn", row_number().over(
                        Window.partitionBy("table_name", "pk")
                                .orderBy(col("source_ts_ms").desc(), col("bronze_object").desc())))
                .filter(col("rn").equalTo(1))
                // op=d latest-wins: the PK disappears for the day (documented contract)
                .filter(col("op").notEqual("d"))
                .drop("rn", "pk");
    }

    /** Same semantics as latestPerPk, expressed purely for tests/selftest (no Spark). */
    public static <T> List<T> latestPerKeyReference(List<T> rows,
            java.util.function.Function<T, String> key,
            java.util.function.Function<T, Long> ts,
            java.util.function.Function<T, String> tiebreak) {
        java.util.Map<String, T> latest = new java.util.TreeMap<>();
        java.util.Map<String, String> tiebreaks = new java.util.TreeMap<>();
        for (T row : rows) {
            String k = key.apply(row);
            Long t = ts.apply(row);
            String current = tiebreaks.get(k);
            boolean take = !latest.containsKey(k)
                    || t > ts.apply(latest.get(k))
                    || (t.equals(ts.apply(latest.get(k))) && tiebreak.apply(row).compareTo(current) > 0);
            if (take) {
                latest.put(k, row);
                tiebreaks.put(k, tiebreak.apply(row));
            }
        }
        return List.copyOf(latest.values());
    }
}

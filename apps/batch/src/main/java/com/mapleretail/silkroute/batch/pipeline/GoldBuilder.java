package com.mapleretail.silkroute.batch.pipeline;

import java.time.LocalDate;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapleretail.silkroute.batch.JobConfig;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;

/**
 * Gold star schema (ADR-0006 decision 3): fact_orders, fact_order_lines,
 * dim_sku, dim_store, dim_date - Parquet, partitioned dt=<date>, written with
 * dynamic-partition overwrite so ONLY the business date's partitions are
 * replaced (bronze is never touched; silver is rebuilt for the date first).
 * Dims derive from the DQ-PASSED silver of the day.
 */
public final class GoldBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(GoldBuilder.class);

    private GoldBuilder() {
    }

    public static void build(SparkSession spark, JobConfig cfg, String businessDate, String dt,
            Dataset<Row> passOrders, Dataset<Row> passLines) {
        int dateKey = Integer.parseInt(dt);
        int dayOfWeek = LocalDate.parse(businessDate).getDayOfWeek().getValue(); // ISO: 1=Mon..7=Sun

        Dataset<Row> factOrders = passOrders.select(
                col("order_id"), col("store_id"), col("region"), col("customer_ref"),
                col("channel"), col("source_system"), col("correlation_id"),
                col("total_amount_minor"), col("currency"))
                .withColumn("date_key", lit(dateKey))
                .withColumn("dt", lit(dt));

        Dataset<Row> factOrderLines = passLines.select(
                col("order_id"), col("line_no"), col("sku_id"), col("quantity"),
                col("unit_price_minor"), col("currency"), col("line_total_minor"))
                .withColumn("dt", lit(dt));

        Dataset<Row> dimSku = passLines.select(col("sku_id")).distinct()
                .withColumn("dt", lit(dt));

        Dataset<Row> dimStore = passOrders.select(col("store_id"), col("region")).distinct()
                .withColumn("dt", lit(dt));

        Dataset<Row> dimDate = spark.createDataFrame(java.util.List.of(
                        org.apache.spark.sql.RowFactory.create(dateKey,
                                java.sql.Date.valueOf(businessDate), dayOfWeek, dt)),
                org.apache.spark.sql.types.DataTypes.createStructType(java.util.List.of(
                        org.apache.spark.sql.types.DataTypes.createStructField("date_key",
                                org.apache.spark.sql.types.DataTypes.IntegerType, false),
                        org.apache.spark.sql.types.DataTypes.createStructField("cal_date",
                                org.apache.spark.sql.types.DataTypes.DateType, false),
                        org.apache.spark.sql.types.DataTypes.createStructField("day_of_week",
                                org.apache.spark.sql.types.DataTypes.IntegerType, false),
                        org.apache.spark.sql.types.DataTypes.createStructField("dt",
                                org.apache.spark.sql.types.DataTypes.StringType, false))));

        factOrders.write().mode("overwrite").partitionBy("dt").parquet(cfg.goldPath("fact_orders"));
        factOrderLines.write().mode("overwrite").partitionBy("dt").parquet(cfg.goldPath("fact_order_lines"));
        dimSku.write().mode("overwrite").partitionBy("dt").parquet(cfg.goldPath("dim_sku"));
        dimStore.write().mode("overwrite").partitionBy("dt").parquet(cfg.goldPath("dim_store"));
        dimDate.write().mode("overwrite").partitionBy("dt").parquet(cfg.goldPath("dim_date"));

        LOG.info("GOLD-WRITTEN dt={} factOrders={} factOrderLines={} dimSku={} dimStore={} dimDate=1 under {}",
                dt, factOrders.count(), factOrderLines.count(), dimSku.count(), dimStore.count(), cfg.goldPath(""));
    }
}

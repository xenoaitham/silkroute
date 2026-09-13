package com.mapleretail.silkroute.batch;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.SparkSession;

/**
 * SparkSession factory: local[*] (SPARK_MASTER override), UI DISABLED, every
 * bind loopback (the shared sim host must gain zero listening sockets), UTC
 * session timezone (C5), and the s3a wiring for the MinIO/OSS endpoint.
 */
public final class SparkSessions {

    private SparkSessions() {
    }

    public static SparkSession create(JobConfig cfg) {
        SparkConf conf = new SparkConf()
                .setAppName("silkroute-batch")
                .setMaster(cfg.sparkMaster)
                .set("spark.ui.enabled", "false")
                .set("spark.driver.bindAddress", "127.0.0.1")
                .set("spark.driver.host", "127.0.0.1")
                .set("spark.sql.session.timeZone", "UTC")
                .set("spark.sql.warehouse.dir", "/tmp/silkroute-spark-warehouse")
                .set("spark.local.dir", "/tmp/silkroute-spark-local")
                // idempotent-per-date writes: dynamic mode replaces ONLY the dt=<date> partitions
                .set("spark.sql.sources.partitionOverwriteMode", "dynamic")
                .set("spark.sql.parquet.outputTimestampType", "TIMESTAMP_MICROS");

        // s3a: MinIO in sim (path-style, plain HTTP); OSS S3-compat endpoint in cloud
        boolean https = cfg.s3Endpoint.startsWith("https");
        conf.set("spark.hadoop.fs.s3a.endpoint", cfg.s3Endpoint);
        conf.set("spark.hadoop.fs.s3a.path.style.access", "true");
        conf.set("spark.hadoop.fs.s3a.connection.ssl.enabled", Boolean.toString(https));
        conf.set("spark.hadoop.fs.s3a.access.key", cfg.s3AccessKey);
        conf.set("spark.hadoop.fs.s3a.secret.key", cfg.s3SecretKey);
        conf.set("spark.hadoop.fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
        conf.set("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");

        return SparkSession.builder().config(conf).getOrCreate();
    }
}

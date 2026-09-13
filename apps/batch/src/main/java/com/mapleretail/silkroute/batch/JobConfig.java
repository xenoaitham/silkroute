package com.mapleretail.silkroute.batch;

/**
 * Every knob via ${VAR:default} env indirection (ADR-0001). Defaults are the
 * documented sim dummies; the cloud swap changes ONLY these values.
 */
public final class JobConfig {

    public final String sparkMaster = System.getenv().getOrDefault("SPARK_MASTER", "local[*]");
    public final String s3Endpoint = env("S3_ENDPOINT", "http://127.0.0.1:9000");
    public final String s3AccessKey = env("S3_ACCESS_KEY", "silkroute");
    public final String s3SecretKey = env("S3_SECRET_KEY", "silkroute-secret");
    public final String bronzeBucket = env("LAKE_BRONZE_BUCKET", "silkroute-sg-bronze");
    public final String silverBucket = env("LAKE_SILVER_BUCKET", "silkroute-sg-silver");
    public final String goldBucket = env("LAKE_GOLD_BUCKET", "silkroute-sg-gold");

    public final String reconJdbcUrl = env("RECON_JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/silkroute_oms?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true");
    public final String reconDbUser = env("RECON_DB_USER", "silkroute_oms");
    public final String reconDbPassword = env("RECON_DB_PASSWORD", "oms-pass-2026");

    public final String dqJdbcUrl = env("DQ_JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/silkroute_lake?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true");
    public final String dqDbUser = env("DQ_DB_USER", "silkroute_oms");
    public final String dqDbPassword = env("DQ_DB_PASSWORD", "oms-pass-2026");

    public final String reconReportPath = env("RECON_REPORT_PATH", "/tmp/silkroute-recon-report.json");
    public final String metricsFile = System.getenv("METRICS_FILE");

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public String silverOrdersPath() {
        return "s3a://" + silverBucket + "/silver/orders";
    }

    public String silverOrderLinesPath() {
        return "s3a://" + silverBucket + "/silver/order_lines";
    }

    public String goldPath(String table) {
        return "s3a://" + goldBucket + "/gold/" + table;
    }

    public String bronzeReadPath(String dt) {
        return "s3a://" + bronzeBucket + "/region=*/table=*/dt=" + dt + "/*";
    }
}

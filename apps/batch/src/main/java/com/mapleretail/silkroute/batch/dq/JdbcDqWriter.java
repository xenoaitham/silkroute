package com.mapleretail.silkroute.batch.dq;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapleretail.silkroute.batch.recon.ReconMath;

/**
 * Plain-JDBC writer for the DQ bookkeeping in MySQL silkroute_lake (ADR-0006
 * decision 3): dq_quarantine (violations EXCLUDED from gold),
 * dq_results (per-rule counters) and recon_report (reconciliation rows).
 * The schema is ensured here too (CREATE TABLE IF NOT EXISTS) so the batch is
 * self-sufficient after `etl-setup` granted the writer user.
 *
 * NOTE: the quarantine timestamp column is spelled `quaranted_at` — exactly as
 * specified in the Phase-3 build brief (sic); kept verbatim for contract parity.
 */
public final class JdbcDqWriter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcDqWriter.class);

    private static final String DDL_QUARANTINE = """
            CREATE TABLE IF NOT EXISTS dq_quarantine (
              run_id VARCHAR(64) NOT NULL,
              rule_id VARCHAR(32) NOT NULL,
              table_name VARCHAR(64) NOT NULL,
              row_key VARCHAR(255) NOT NULL,
              reason VARCHAR(512) NOT NULL,
              violating_row_json JSON NULL,
              quaranted_at TIMESTAMP(3) NOT NULL,
              KEY idx_run (run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """;

    private static final String DDL_RESULTS = """
            CREATE TABLE IF NOT EXISTS dq_results (
              run_id VARCHAR(64) NOT NULL,
              rule_id VARCHAR(32) NOT NULL,
              table_name VARCHAR(64) NOT NULL,
              checked BIGINT NOT NULL,
              violations BIGINT NOT NULL,
              status VARCHAR(8) NOT NULL,
              run_at TIMESTAMP(3) NOT NULL,
              KEY idx_run (run_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """;

    private static final String DDL_RECON = """
            CREATE TABLE IF NOT EXISTS recon_report (
              run_id VARCHAR(64) NOT NULL,
              business_date DATE NOT NULL,
              orders_source BIGINT NOT NULL,
              orders_gold BIGINT NOT NULL,
              lines_source BIGINT NOT NULL,
              lines_gold BIGINT NOT NULL,
              totals_json JSON NULL,
              all_match TINYINT NOT NULL,
              run_at TIMESTAMP(3) NOT NULL,
              KEY idx_date (business_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """;

    private static final String INSERT_QUARANTINE = """
            INSERT INTO dq_quarantine (run_id, rule_id, table_name, row_key, reason, violating_row_json, quaranted_at)
            VALUES (?,?,?,?,?,?,?)
            """;
    private static final String INSERT_RESULT = """
            INSERT INTO dq_results (run_id, rule_id, table_name, checked, violations, status, run_at)
            VALUES (?,?,?,?,?,?,?)
            """;
    private static final String INSERT_RECON = """
            INSERT INTO recon_report (run_id, business_date, orders_source, orders_gold, lines_source, lines_gold, totals_json, all_match, run_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """;

    private final Connection connection;

    public JdbcDqWriter(String url, String user, String password) {
        try {
            this.connection = DriverManager.getConnection(url, user, password);
            this.connection.setAutoCommit(true);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(DDL_QUARANTINE);
                stmt.execute(DDL_RESULTS);
                stmt.execute(DDL_RECON);
            }
            LOG.info("DQ-JDBC-READY url={} (schema ensured)", url.replaceAll("\\?.*", ""));
        } catch (SQLException e) {
            throw new IllegalStateException("DQ-JDBC-CONNECT-FAILED " + url.replaceAll("\\?.*", "") + ": " + e.getMessage(), e);
        }
    }

    public void writeQuarantine(String runId, List<Violation> violations, Instant nowUtc) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_QUARANTINE)) {
            for (Violation violation : violations) {
                ps.setString(1, runId);
                ps.setString(2, violation.ruleId());
                ps.setString(3, violation.tableName());
                ps.setString(4, truncate(violation.rowKey(), 255));
                ps.setString(5, truncate(violation.reason(), 512));
                ps.setString(6, violation.rowJson());
                ps.setTimestamp(7, Timestamp.from(nowUtc));
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            LOG.info("DQ-QUARANTINE-WRITTEN run={} rows={}", runId, counts.length);
        } catch (SQLException e) {
            throw new IllegalStateException("DQ-QUARANTINE-WRITE-FAILED: " + e.getMessage(), e);
        }
    }

    public void writeResults(String runId, List<DqResult> results, Instant nowUtc) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_RESULT)) {
            for (DqResult result : results) {
                ps.setString(1, runId);
                ps.setString(2, result.ruleId());
                ps.setString(3, result.tableName());
                ps.setLong(4, result.checked());
                ps.setLong(5, result.violations());
                ps.setString(6, result.status());
                ps.setTimestamp(7, Timestamp.from(nowUtc));
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.info("DQ-RESULTS-WRITTEN run={} rules={}", runId, results.size());
        } catch (SQLException e) {
            throw new IllegalStateException("DQ-RESULTS-WRITE-FAILED: " + e.getMessage(), e);
        }
    }

    public void writeReconReport(String runId, ReconMath.FullReport report, String totalsJson, Instant nowUtc) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_RECON)) {
            ps.setString(1, runId);
            ps.setString(2, report.businessDate());
            ps.setLong(3, report.orders().source());
            ps.setLong(4, report.orders().gold());
            ps.setLong(5, report.lines().source());
            ps.setLong(6, report.lines().gold());
            ps.setString(7, totalsJson);
            ps.setInt(8, report.allMatch() ? 1 : 0);
            ps.setTimestamp(9, Timestamp.from(nowUtc));
            ps.executeUpdate();
            LOG.info("RECON-REPORT-WRITTEN run={} businessDate={} allMatch={}", runId, report.businessDate(), report.allMatch());
        } catch (SQLException e) {
            throw new IllegalStateException("RECON-REPORT-WRITE-FAILED: " + e.getMessage(), e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.error("DQ-JDBC-CLOSE-ERROR {}", e.toString());
        }
    }
}

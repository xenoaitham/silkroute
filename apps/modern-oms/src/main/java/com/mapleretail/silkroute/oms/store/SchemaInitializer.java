package com.mapleretail.silkroute.oms.store;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ensures the CDC-source schema exists on boot (CREATE TABLE IF NOT EXISTS,
 * plain JDBC — no JPA, no migration tooling). Idempotent: running against an
 * existing silkroute_oms changes nothing.
 *
 * oms_order carries the full event copy (raw_event JSON) + Kafka lineage
 * columns; oms_order_line carries quantity x unit_price_minor as integer
 * line_total_minor (C5) and the denormalized region for region tagging (C1).
 */
@Component
@Order(0) // before the consumer starts writing
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    static final String DDL_ORDER = """
            CREATE TABLE IF NOT EXISTS oms_order (
              order_id            VARCHAR(64)  NOT NULL,
              external_order_ref  VARCHAR(64)  NOT NULL,
              source_system       VARCHAR(64)  NOT NULL,
              store_id            VARCHAR(16)  NULL,
              region              VARCHAR(2)   NULL,
              customer_ref        VARCHAR(64)  NULL,
              channel             VARCHAR(16)  NULL,
              status              VARCHAR(32)  NULL,
              reservation_id      VARCHAR(64)  NULL,
              total_amount_minor  BIGINT       NULL,
              currency            CHAR(3)      NULL,
              correlation_id      VARCHAR(64)  NULL,
              kafka_partition     INT          NULL,
              kafka_offset        BIGINT       NULL,
              raw_event           JSON         NULL,
              ingested_at         TIMESTAMP(3) NOT NULL,
              PRIMARY KEY (order_id),
              UNIQUE KEY uk_biz (source_system, external_order_ref),
              KEY idx_ingested_at (ingested_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """;

    static final String DDL_LINE = """
            CREATE TABLE IF NOT EXISTS oms_order_line (
              line_id           BIGINT      NOT NULL AUTO_INCREMENT,
              order_id          VARCHAR(64) NOT NULL,
              line_no           INT         NOT NULL,
              sku_id            VARCHAR(16) NOT NULL,
              quantity          INT         NOT NULL,
              unit_price_minor  BIGINT      NOT NULL,
              currency          CHAR(3)     NOT NULL,
              line_total_minor  BIGINT      NOT NULL,
              region            VARCHAR(2)  NULL,
              PRIMARY KEY (line_id),
              UNIQUE KEY uk_line (order_id, line_no),
              KEY idx_order (order_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """;

    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(DDL_ORDER);
            stmt.execute(DDL_LINE);
        }
        LOG.info("OMS-SCHEMA-READY database=oms_order,oms_order_line ensured (CREATE TABLE IF NOT EXISTS)");
    }
}

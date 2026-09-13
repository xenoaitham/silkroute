package com.mapleretail.silkroute.oms.store;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import javax.sql.DataSource;

import com.mapleretail.silkroute.oms.event.OrderEventData;
import com.mapleretail.silkroute.oms.event.RecordMeta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC event store (plain SQL, no JPA). Transactional: the order row and ALL
 * its lines commit or roll back together.
 *
 * WAR STORY (S9, found by the live mini-run): the first implementation opened
 * the connection with {@code try (var conn = DataSourceUtils.getConnection(...))}
 * inside the transaction — try-with-resources closed the TRANSACTION-BOUND
 * Hikari proxy directly (bypassing DataSourceUtils' close semantics), so every
 * commit failed with "Connection is closed" and the record retried forever.
 * Fix: statements run through JdbcTemplate, which participates in the Spring
 * transaction and manages its own connection lifecycle correctly.
 *
 * Idempotency is belt-and-braces (ADR-0006 decision 2):
 *  1. a code-level business-key check (source_system, external_order_ref) — a
 *     duplicate REPLAY-SKIPs before touching lines, so no partial dupes;
 *  2. INSERT ... ON DUPLICATE KEY UPDATE <pk>=<pk> — a deliberate no-op that
 *     can never double-insert even if two consumers raced between the check
 *     and the insert.
 */
@Component
public class JdbcOrderEventStore implements OrderEventStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcOrderEventStore.class);

    private static final String INSERT_ORDER = """
            INSERT INTO oms_order
              (order_id, external_order_ref, source_system, store_id, region, customer_ref, channel,
               status, reservation_id, total_amount_minor, currency, correlation_id,
               kafka_partition, kafka_offset, raw_event, ingested_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE order_id = order_id
            """;

    private static final String INSERT_LINE = """
            INSERT INTO oms_order_line
              (order_id, line_no, sku_id, quantity, unit_price_minor, currency, line_total_minor, region)
            VALUES (?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE line_id = line_id
            """;

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public JdbcOrderEventStore(DataSource dataSource) {
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Outcome store(OrderEventData event, RecordMeta meta) {
        Boolean replay = tx.execute(status -> {
            if (exists(event)) {
                LOG.info("REPLAY-SKIP sourceSystem={} externalOrderRef={} orderId={} topic={} partition={} offset={}",
                        event.getSourceSystem(), event.getExternalOrderRef(), event.getOrderId(),
                        meta.topic(), meta.partition(), meta.offset());
                return Boolean.TRUE;
            }
            insertOrder(event, meta);
            int lineNo = 0;
            for (OrderEventData.Line line : event.getLines()) {
                insertLine(event, lineNo, line);
                lineNo++;
            }
            return Boolean.FALSE;
        });
        boolean skipped = Boolean.TRUE.equals(replay);
        if (!skipped) {
            LOG.info("OMS-ORDER-STORED orderId={} lines={} sourceSystem={} externalOrderRef={} region={} totalMinor={} currency={} partition={} offset={}",
                    event.getOrderId(), event.getLines().size(), event.getSourceSystem(), event.getExternalOrderRef(),
                    event.getRegion(), event.getTotalAmountMinor(), event.getCurrency(), meta.partition(), meta.offset());
        }
        return skipped ? Outcome.skipped() : Outcome.stored();
    }

    private boolean exists(OrderEventData event) {
        Integer found = jdbc.query(
                "SELECT 1 FROM oms_order WHERE source_system = ? AND external_order_ref = ? LIMIT 1",
                (PreparedStatement ps) -> {
                    ps.setString(1, event.getSourceSystem());
                    ps.setString(2, event.getExternalOrderRef());
                },
                (rs, rowNum) -> rs.getInt(1))
                .stream().findFirst().orElse(null);
        return found != null;
    }

    private void insertOrder(OrderEventData event, RecordMeta meta) {
        jdbc.update(INSERT_ORDER, (PreparedStatement ps) -> {
            ps.setString(1, event.getOrderId());
            ps.setString(2, event.getExternalOrderRef());
            ps.setString(3, event.getSourceSystem());
            setNullableString(ps, 4, event.getStoreId());
            setNullableString(ps, 5, event.getRegion());
            // C1: stored verbatim — for CN this is ALREADY the msk-* pseudonym from the ESB egress.
            setNullableString(ps, 6, event.getCustomerRef());
            setNullableString(ps, 7, event.getChannel());
            setNullableString(ps, 8, event.getStatus());
            setNullableString(ps, 9, event.getReservationId());
            if (event.getTotalAmountMinor() != null) {
                ps.setLong(10, event.getTotalAmountMinor());
            } else {
                ps.setNull(10, Types.BIGINT);
            }
            setNullableString(ps, 11, event.getCurrency());
            setNullableString(ps, 12, event.getCorrelationId());
            ps.setInt(13, meta.partition());
            ps.setLong(14, meta.offset());
            setNullableString(ps, 15, event.getRawEventJson());
            // connectionTimeZone=UTC on the JDBC URL makes this wall-clock UTC, C5 timezone-explicit.
            ps.setTimestamp(16, new Timestamp(meta.timestamp()));
        });
    }

    private void insertLine(OrderEventData event, int lineNo, OrderEventData.Line line) {
        jdbc.update(INSERT_LINE, (PreparedStatement ps) -> {
            ps.setString(1, event.getOrderId());
            ps.setInt(2, lineNo);
            ps.setString(3, line.skuId());
            ps.setInt(4, line.quantity());
            ps.setLong(5, line.unitPriceMinor());
            ps.setString(6, line.currency());
            ps.setLong(7, line.lineTotalMinor()); // integer math from OrderEventData.Line (C5)
            setNullableString(ps, 8, event.getRegion()); // denormalized for region tagging (C1)
        });
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}

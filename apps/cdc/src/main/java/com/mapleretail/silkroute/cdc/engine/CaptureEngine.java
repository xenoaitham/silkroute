package com.mapleretail.silkroute.cdc.engine;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapleretail.silkroute.cdc.common.Env;

/**
 * Main A — the embedded Debezium engine (ADR-0006 decision 1).
 *
 * Captures MySQL binlog changes of silkroute_oms.oms_order / oms_order_line
 * (MySqlConnector, snapshot.mode=initial, offsets + schema history in local
 * file stores — deterministic kill/restart for chaos beats) and publishes each
 * FULL envelope (op, ts_ms, source{ts_ms,table,server}, before, after) as JSON
 * (schemas.enable=false) to ${KAFKA_CDC_TOPIC}. The per-record
 *
 *   CDC-CAPTURE op=... table=... sourceTsMs=... server=...
 *
 * log line is the binlog PROOF: these records can only come from the MySQL
 * binlog stream (the connector user has SELECT+REPLICATION SLAVE/CLIENT and
 * NO poll/table-scan fallback exists in this codebase).
 *
 * Publish is synchronous (producer.send().get()): a record is "published"
 * only once the broker acked — that is the freshness contract's success point.
 * A publish or engine failure is LOUD: the completion callback logs and the
 * process exits non-zero (offsets are only flushed for successfully handled
 * batches).
 */
public final class CaptureEngine {

    private static final Logger LOG = LoggerFactory.getLogger(CaptureEngine.class);

    public static void main(String[] args) throws Exception {
        String bootstrap = Env.get("KAFKA_BOOTSTRAP", "127.0.0.1:39092");
        String cdcTopic = Env.get("KAFKA_CDC_TOPIC", "silkroute.cdc.oms");
        String serverName = Env.get("CDC_SERVER_NAME", "silkroute-oms");
        String engineName = Env.get("CDC_ENGINE_NAME", "silkroute-cdc");
        String mysqlHost = Env.get("CDC_MYSQL_HOST", "127.0.0.1");
        int mysqlPort = Env.getInt("CDC_MYSQL_PORT", 3306);
        String mysqlUser = Env.get("CDC_MYSQL_USER", "silkroute_cdc");
        String mysqlPassword = Env.get("CDC_MYSQL_PASSWORD", "cdc-pass-2026");
        String offsetFile = Env.get("CDC_OFFSET_FILE", "/tmp/silkroute-cdc-offsets.json");
        String historyFile = Env.get("CDC_SCHEMA_HISTORY_FILE", "/tmp/silkroute-cdc-history.dat");
        long serverId = Env.getLong("CDC_SERVER_ID", 17234001L);
        String snapshotMode = Env.get("CDC_SNAPSHOT_MODE", "initial");
        String tableInclude = Env.get("CDC_TABLE_INCLUDE", "silkroute_oms.oms_order,silkroute_oms.oms_order_line");
        long heartbeatSeconds = Env.getLong("METRIC_HEARTBEAT_SECONDS", 30);
        String metricsFile = System.getenv("METRICS_FILE");

        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps(bootstrap));
        FreshnessEmitter freshness = new FreshnessEmitter(metricsFile);

        Properties props = new Properties();
        props.setProperty("name", engineName);
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", offsetFile);
        props.setProperty("offset.flush.interval.ms", "5000");
        props.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory");
        props.setProperty("schema.history.internal.file.filename", historyFile);
        props.setProperty("database.hostname", mysqlHost);
        props.setProperty("database.port", String.valueOf(mysqlPort));
        props.setProperty("database.user", mysqlUser);
        props.setProperty("database.password", mysqlPassword);
        // deterministic and distinct from the MySQL server_id (1 on sim) — the replication client identity
        props.setProperty("database.server.id", String.valueOf(serverId));
        props.setProperty("topic.prefix", serverName);
        props.setProperty("database.include.list", Env.get("CDC_DATABASE_INCLUDE", "silkroute_oms"));
        props.setProperty("table.include.list", tableInclude);
        props.setProperty("snapshot.mode", snapshotMode);
        // keep the record stream data-only: no schema-change, no heartbeat, no tombstone records
        props.setProperty("include.schema.changes", "false");
        props.setProperty("tombstones.on.delete", "false");
        props.setProperty("key.converter", "org.apache.kafka.connect.json.JsonConverter");
        props.setProperty("value.converter", "org.apache.kafka.connect.json.JsonConverter");
        props.setProperty("key.converter.schemas.enable", "false");
        props.setProperty("value.converter.schemas.enable", "false");

        CountDownLatch stopped = new CountDownLatch(1);

        DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying((List<ChangeEvent<String, String>> records,
                            DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer) -> {
                    try {
                        handleBatch(records, producer, cdcTopic, freshness);
                        // file-backed offsets advance ONLY for records that were fully published above
                        for (ChangeEvent<String, String> record : records) {
                            committer.markProcessed(record);
                        }
                        committer.markBatchFinished();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("engine interrupted while committing offsets", e);
                    }
                })
                .using((success, message, error) -> {
                    if (error != null) {
                        LOG.error("CDC-ENGINE-FAILED message={} error={}", message, error.toString(), error);
                    } else {
                        LOG.info("CDC-ENGINE-STOP success={} message={}", success, message);
                    }
                    stopped.countDown();
                })
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("CDC-ENGINE-SHUTDOWN requested");
            try {
                engine.close();
            } catch (Exception e) {
                LOG.error("CDC-ENGINE-CLOSE-ERROR {}", e.toString());
            }
        }, "cdc-engine-shutdown"));

        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cdc-metric-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                freshness.heartbeat(System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOG.error("CDC-HEARTBEAT-ERROR {}", e.toString());
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

        LOG.info("CDC-ENGINE-START name={} topicPrefix={} publishTopic={} bootstrap={} mysql={}:{} tables={} snapshot={} offsetFile={} historyFile={} heartbeatSeconds={}",
                engineName, serverName, cdcTopic, bootstrap, mysqlHost, mysqlPort, tableInclude, snapshotMode, offsetFile, historyFile, heartbeatSeconds);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "cdc-engine"));
        executor.execute(engine);
        stopped.await();
        heartbeat.shutdownNow();
        producer.close(Duration.ofSeconds(10));
        executor.shutdownNow();
        LOG.info("CDC-ENGINE-EXIT");
    }

    /** One Debezium batch: publish every record synchronously, then emit the freshness metric for the batch. */
    static void handleBatch(List<ChangeEvent<String, String>> records, KafkaProducer<String, String> producer,
            String cdcTopic, FreshnessEmitter freshness) {
        long batchMaxSourceTsMs = 0L;
        int published = 0;
        for (ChangeEvent<String, String> record : records) {
            String value = record.value();
            if (value == null) {
                // defensive: tombstones are disabled, but never forward a null envelope
                LOG.info("CDC-TOMBSTONE-SKIP destination={}", record.destination());
                continue;
            }
            final Envelope envelope;
            try {
                envelope = Envelope.fromJson(value);
            } catch (Exception e) {
                // a non-envelope record cannot be routed or reconciled — fail loudly
                throw new IllegalStateException("CDC-ENVELOPE-UNPARSEABLE destination=" + record.destination(), e);
            }
            LOG.info("CDC-CAPTURE op={} table={} sourceTsMs={} server={}",
                    envelope.op(), envelope.table(), envelope.sourceTsMs(), envelope.server());
            try {
                producer.send(new ProducerRecord<>(cdcTopic, record.key(), value)).get();
            } catch (Exception e) {
                throw new IllegalStateException("CDC-PUBLISH-FAILED topic=" + cdcTopic
                        + " table=" + envelope.table() + " op=" + envelope.op(), e);
            }
            published++;
            Long sourceTsMs = envelope.sourceTsMs();
            if (sourceTsMs != null && sourceTsMs > batchMaxSourceTsMs) {
                batchMaxSourceTsMs = sourceTsMs;
            }
        }
        if (published > 0 && batchMaxSourceTsMs > 0) {
            // C4 contract: measured at emission time, against the latest successfully published record
            freshness.onBatchPublished(batchMaxSourceTsMs, System.currentTimeMillis());
        }
    }

    private static Properties producerProps(String bootstrap) {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", bootstrap);
        p.setProperty("key.serializer", StringSerializer.class.getName());
        p.setProperty("value.serializer", StringSerializer.class.getName());
        p.setProperty("acks", "all");
        p.setProperty("enable.idempotence", "true");
        p.setProperty("linger.ms", "5");
        p.setProperty("max.block.ms", "10000");
        p.setProperty("request.timeout.ms", "15000");
        p.setProperty("delivery.timeout.ms", "30000");
        return p;
    }
}

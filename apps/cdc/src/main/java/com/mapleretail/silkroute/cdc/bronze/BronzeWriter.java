package com.mapleretail.silkroute.cdc.bronze;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.mapleretail.silkroute.cdc.common.Env;
import com.mapleretail.silkroute.cdc.engine.Envelope;

/**
 * Main B — bronze landing (ADR-0006 decision 3): consume the CDC topic and
 * land RAW Debezium envelopes write-once as JSONL objects on the S3 API
 * (MinIO in sim; OSS S3-compat surface in cloud, ADR-0001).
 *
 * Immutability by construction: putObject ONLY — this class has no delete,
 * no copy, no overwrite code path, and keys embed (topic, partition, offset,
 * uuid4) so a replay writes NEW keys; downstream (silver) dedups by PK +
 * source.ts_ms.
 *
 * Offsets are committed ONLY after every object of the poll batch landed
 * (at-least-once). A record whose region cannot be determined or is outside
 * ${BRONZE_ALLOWED_REGIONS} fails the writer FAST and LOUD — an untagged
 * object would break the C1 residency story, so it must never land.
 */
public final class BronzeWriter {

    private static final Logger LOG = LoggerFactory.getLogger(BronzeWriter.class);

    /** One raw envelope plus the read-position provenance for the object name. */
    record Landing(Envelope envelope, String rawValue, String topic, int partition, long offset, long sourceTsMs) {
    }

    public static void main(String[] args) {
        String bootstrap = Env.get("KAFKA_BOOTSTRAP", "127.0.0.1:39092");
        String topic = Env.get("KAFKA_CDC_TOPIC", "silkroute.cdc.oms");
        String group = Env.get("CDC_BRONZE_GROUP", "silkroute-bronze-writer");
        String autoOffsetReset = Env.get("CDC_BRONZE_AUTO_OFFSET_RESET", "earliest");
        int maxPollRecords = Env.getInt("CDC_BRONZE_MAX_POLL_RECORDS", 500);
        String endpoint = Env.get("S3_ENDPOINT", "http://127.0.0.1:9000");
        String accessKey = Env.get("S3_ACCESS_KEY", "silkroute");
        String secretKey = Env.get("S3_SECRET_KEY", "silkroute-secret");
        String bucket = Env.get("LAKE_BRONZE_BUCKET", "silkroute-sg-bronze");
        Set<String> allowedRegions = Set.of(Env.get("BRONZE_ALLOWED_REGIONS", "CA,SG,CN").split(","));

        AmazonS3 s3 = s3Client(endpoint, accessKey, secretKey);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(bootstrap, group, autoOffsetReset, maxPollRecords));

        Runtime.getRuntime().addShutdownHook(new Thread(consumer::wakeup, "bronze-writer-shutdown"));

        LOG.info("BRONZE-WRITER-START group={} topic={} bootstrap={} bucket={} endpoint={} allowedRegions={} autoOffsetReset={}",
                group, topic, bootstrap, bucket, endpoint, allowedRegions, autoOffsetReset);

        try {
            consumer.subscribe(List.of(topic));
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                if (records.isEmpty()) {
                    continue;
                }
                Map<String, List<Landing>> groups = new LinkedHashMap<>();
                for (ConsumerRecord<String, String> record : records) {
                    Landing landing = toLanding(record, bucket, allowedRegions);
                    if (landing == null) {
                        continue; // tombstone: nothing to land
                    }
                    Envelope envelope = landing.envelope();
                    String groupKey = landingKey(envelope.region(), envelope.table(), BronzeKeys.dtFolder(landing.sourceTsMs()));
                    groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(landing);
                }
                for (Map.Entry<String, List<Landing>> entry : groups.entrySet()) {
                    land(entry.getValue(), s3, bucket);
                }
                // commit ONLY now: every object of this poll batch is durably landed
                consumer.commitSync();
            }
        } catch (WakeupException e) {
            LOG.info("BRONZE-WRITER-STOP requested (shutdown hook)");
        } catch (Exception e) {
            LOG.error("BRONZE-WRITER-FAILED error={}", e.toString(), e);
            System.exit(2);
        } finally {
            try {
                consumer.close(Duration.ofSeconds(10));
            } catch (RuntimeException e) {
                LOG.error("BRONZE-WRITER-CLOSE-ERROR {}", e.toString());
            }
            LOG.info("BRONZE-WRITER-EXIT");
        }
    }

    private static Landing toLanding(ConsumerRecord<String, String> record, String bucket, Set<String> allowedRegions) {
        String value = record.value();
        if (value == null) {
            LOG.info("BRONZE-TOMBSTONE-SKIP topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
            return null;
        }
        final Envelope envelope;
        try {
            envelope = Envelope.fromJson(value);
        } catch (Exception e) {
            throw new IllegalStateException("BRONZE-REJECT unparseable envelope topic=" + record.topic()
                    + " partition=" + record.partition() + " offset=" + record.offset(), e);
        }
        String region = envelope.region();
        String table = envelope.table();
        Long sourceTsMs = envelope.sourceTsMs();
        if (region.isBlank() || table.isBlank() || sourceTsMs == null) {
            throw new IllegalStateException("BRONZE-REJECT missing routing fields (region/table/sourceTsMs) topic="
                    + record.topic() + " partition=" + record.partition() + " offset=" + record.offset()
                    + " — an object without its region tag can never land (C1)");
        }
        if (!allowedRegions.contains(region)) {
            throw new IllegalStateException("BRONZE-REJECT region=" + region + " is not in the allowed set " + allowedRegions
                    + " for bucket " + bucket + " — refusing to land an untagged/mis-tagged object (C1)");
        }
        return new Landing(envelope, value, record.topic(), record.partition(), record.offset(), sourceTsMs);
    }

    private static void land(List<Landing> landings, AmazonS3 s3, String bucket) {
        Landing first = landings.get(0);
        Landing last = landings.get(landings.size() - 1);
        Envelope envelope = first.envelope();
        String key = BronzeKeys.objectKey(
                envelope.region(), envelope.table(), BronzeKeys.dtFolder(first.sourceTsMs()),
                first.topic(), first.partition(), first.offset(), UUID.randomUUID().toString());
        StringBuilder jsonl = new StringBuilder();
        long firstTsMs = Long.MAX_VALUE;
        long lastTsMs = Long.MIN_VALUE;
        for (Landing landing : landings) {
            jsonl.append(landing.rawValue()).append('\n');
            firstTsMs = Math.min(firstTsMs, landing.sourceTsMs());
            lastTsMs = Math.max(lastTsMs, landing.sourceTsMs());
        }
        byte[] body = jsonl.toString().getBytes(StandardCharsets.UTF_8);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(body.length);
        metadata.setContentType("application/x-ndjson");
        try {
            s3.putObject(bucket, key, new java.io.ByteArrayInputStream(body), metadata);
        } catch (RuntimeException e) {
            throw new IllegalStateException("BRONZE-LAND-FAILED bucket=" + bucket + " key=" + key
                    + " — check that the bucket exists and the S3 endpoint/credentials are correct", e);
        }
        LOG.info("BRONZE-LAND region={} table={} object={} records={} firstTsMs={} lastTsMs={}",
                envelope.region(), envelope.table(), key, landings.size(), firstTsMs, lastTsMs);
    }

    private static String landingKey(String region, String table, String dt) {
        return region + "/" + table + "/" + dt;
    }

    /** S3 API client, path-style (MinIO); region string is a sigv4 dummy for the sim endpoint. */
    static AmazonS3 s3Client(String endpoint, String accessKey, String secretKey) {
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
                .withPathStyleAccessEnabled(true)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .withClientConfiguration(new ClientConfiguration().withTcpKeepAlive(true))
                .disableChunkedEncoding()
                .build();
    }

    private static Properties consumerProps(String bootstrap, String group, String autoOffsetReset, int maxPollRecords) {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", bootstrap);
        p.setProperty("group.id", group);
        p.setProperty("key.deserializer", StringDeserializer.class.getName());
        p.setProperty("value.deserializer", StringDeserializer.class.getName());
        p.setProperty("enable.auto.commit", "false");
        p.setProperty("auto.offset.reset", autoOffsetReset);
        p.setProperty("max.poll.records", String.valueOf(maxPollRecords));
        return p;
    }
}

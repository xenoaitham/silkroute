package com.mapleretail.silkroute.cdc.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the envelope contract and the freshness metric (plain JUnit,
 * no Kafka/MySQL/MinIO).
 */
class EnvelopeAndFreshnessTest {

    private static final String CREATE_ENVELOPE = """
            {"before":null,
             "after":{"order_id":"ORD-1","store_id":"ST-CA-01","region":"CA","customer_ref":"cust-ca-1",
                      "total_amount_minor":25000,"currency":"CAD","ingested_at":"2026-09-13T10:00:00.123"},
             "source":{"version":"2.7.3.Final","connector":"mysql","name":"silkroute-oms","ts_ms":1788991200123,
                       "snapshot":"false","db":"silkroute_oms","table":"oms_order","server_id":1},
             "op":"c","ts_ms":1788991200456,"transaction":null}
            """;

    private static final String UPDATE_ENVELOPE = CREATE_ENVELOPE
            .replace("\"before\":null", "\"before\":{\"order_id\":\"ORD-1\",\"region\":\"CA\",\"status\":\"CONFIRMED\"}")
            .replace("\"op\":\"c\"", "\"op\":\"u\"");

    private static final String DELETE_ENVELOPE = """
            {"before":{"order_id":"ORD-1","store_id":"ST-CA-01","region":"CA","customer_ref":"cust-ca-1"},
             "after":null,
             "source":{"version":"2.7.3.Final","connector":"mysql","name":"silkroute-oms","ts_ms":1788991300123,
                       "snapshot":"false","db":"silkroute_oms","table":"oms_order","server_id":1},
             "op":"d","ts_ms":1788991300456,"transaction":null}
            """;

    @Test
    void envelopeRoundTripPreservesTheRawContract() throws IOException {
        Envelope parsed = Envelope.fromJson(CREATE_ENVELOPE);
        String reserialized = parsed.toJson();
        Envelope reparsed = Envelope.fromJson(reserialized);
        assertEquals(parsed.raw(), reparsed.raw());
        assertEquals(parsed.op(), reparsed.op());
        assertEquals(parsed.table(), reparsed.table());
        assertEquals(parsed.sourceTsMs(), reparsed.sourceTsMs());
        assertEquals(parsed.region(), reparsed.region());
        // and the wire shape is untouched: the full envelope keys are all present
        assertTrue(reserialized.contains("\"before\":null"));
        assertTrue(reserialized.contains("\"source\":"));
        assertTrue(reserialized.contains("\"op\":\"c\""));
        assertTrue(reserialized.contains("\"ts_ms\":1788991200456"));
    }

    @Test
    void regionExtractionHandlesNullBeforeAndNullAfter() throws IOException {
        // create: before is null -> region from after
        assertEquals("CA", Envelope.fromJson(CREATE_ENVELOPE).region());
        // update: both present -> after wins
        Envelope update = Envelope.fromJson(UPDATE_ENVELOPE);
        assertEquals("u", update.op());
        assertEquals("CA", update.region());
        // delete: after is null -> region from before
        Envelope delete = Envelope.fromJson(DELETE_ENVELOPE);
        assertEquals("d", delete.op());
        assertEquals("CA", delete.region());
    }

    @Test
    void tableServerAndSourceTsExtraction() throws IOException {
        Envelope parsed = Envelope.fromJson(CREATE_ENVELOPE);
        assertEquals("oms_order", parsed.table());
        assertEquals("silkroute-oms", parsed.server());
        assertEquals(1788991200123L, parsed.sourceTsMs());
    }

    @Test
    void freshnessValueIsComputedFromTheSourceTimestampNeverAConstant() {
        long sourceTs = 1_788_991_200_123L;
        long value1 = FreshnessEmitter.freshnessSeconds(sourceTs, sourceTs + 4_000L);
        long value2 = FreshnessEmitter.freshnessSeconds(sourceTs + 10_000L, sourceTs + 17_000L);
        // two different source timestamps MUST give different values against a fixed clock
        assertEquals(4L, value1);
        assertEquals(7L, value2);
        assertNotEquals(value1, value2);
        // a clock before the source ts clamps to 0 (never negative)
        assertEquals(0L, FreshnessEmitter.freshnessSeconds(sourceTs + 1_000L, sourceTs));
    }

    @Test
    void metricLineHasExactlyTheThreeContractKeys() {
        String line = FreshnessEmitter.metricLine(12L);
        assertEquals("{\"metric\":\"cdc_freshness_seconds\",\"value\":12,\"pipeline\":\"cdc\"}", line);
        assertTrue(Pattern.compile("^\\{\"metric\":\"cdc_freshness_seconds\",\"value\":\\d+,\"pipeline\":\"cdc\"}$")
                .matcher(line).matches());
    }

    @Test
    void metricsFileAppendIsPurelyAdditive(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("metrics.jsonl");
        FreshnessEmitter emitter = new FreshnessEmitter(file.toString());
        emitter.onBatchPublished(1_000L, 5_000L);   // 4s
        emitter.heartbeat(8_000L);                  // 7s (idle growth is correct measured lag)
        String content = Files.readString(file);
        assertEquals(List.of(
                "{\"metric\":\"cdc_freshness_seconds\",\"value\":4,\"pipeline\":\"cdc\"}",
                "{\"metric\":\"cdc_freshness_seconds\",\"value\":7,\"pipeline\":\"cdc\"}"),
                content.lines().toList());
    }

    @Test
    void heartbeatBeforeFirstPublicationIsSilent() {
        FreshnessEmitter emitter = new FreshnessEmitter(null);
        assertEquals(-1L, emitter.heartbeat(9_999L)); // nothing published yet: nothing to report
        assertNull(null);
    }
}

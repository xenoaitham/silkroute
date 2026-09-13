package com.mapleretail.silkroute.cdc.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The C4 freshness contract producer (ADR-0006 decision 4, definition verbatim):
 *
 *   cdc_freshness_seconds = now - ts_ms, where ts_ms is the source-commit
 *   timestamp of the latest Debezium record the CDC app SUCCESSFULLY published
 *   to the Kafka CDC topic, measured by the producer at emission time.
 *
 * Emitted as {"metric":"cdc_freshness_seconds","value":N,"pipeline":"cdc"} —
 * the exact contract shape of the Phase-6 pipeline-metrics store (E-019):
 * ONLY these three keys, value in whole seconds (integer; the brief allows
 * 1-decimal but the sim emits integers to keep every number in this repo
 * non-floating-point). The engine emits once per published batch AND on every
 * heartbeat while idle — a growing value under source silence is CORRECT
 * measured lag, not an error.
 *
 * Pure and clock-injectable (all methods take nowMs) so tests can prove the
 * value is COMPUTED from the source timestamp, never a constant.
 */
public final class FreshnessEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(FreshnessEmitter.class);
    public static final String METRIC_NAME = "cdc_freshness_seconds";
    public static final String PIPELINE = "cdc";

    private final String metricsFile;
    private volatile long lastPublishedSourceTsMs;

    public FreshnessEmitter(String metricsFile) {
        this.metricsFile = metricsFile;
    }

    /** Called after a batch was fully published; returns the emitted value in seconds. */
    public synchronized long onBatchPublished(long batchMaxSourceTsMs, long nowMs) {
        lastPublishedSourceTsMs = batchMaxSourceTsMs;
        return emit(batchMaxSourceTsMs, nowMs, "batch");
    }

    /** Idle heartbeat: same value recomputed against the wall clock. No-op before the first publication. */
    public synchronized long heartbeat(long nowMs) {
        long last = lastPublishedSourceTsMs;
        if (last <= 0) {
            return -1;
        }
        return emit(last, nowMs, "heartbeat");
    }

    private long emit(long sourceTsMs, long nowMs, String trigger) {
        long value = freshnessSeconds(sourceTsMs, nowMs);
        String line = metricLine(value);
        LOG.info("CDC-METRIC trigger={} {}", trigger, line);
        System.out.println(line);
        System.out.flush();
        appendToFile(line);
        return value;
    }

    /** Pure computation — the tested core. Never negative; whole seconds. */
    public static long freshnessSeconds(long sourceTsMs, long nowMs) {
        long delta = nowMs - sourceTsMs;
        return delta <= 0 ? 0 : delta / 1000L;
    }

    /** The EXACT contract line: only metric, value, pipeline — no other keys. */
    public static String metricLine(long valueSeconds) {
        return "{\"metric\":\"" + METRIC_NAME + "\",\"value\":" + valueSeconds + ",\"pipeline\":\"" + PIPELINE + "\"}";
    }

    private void appendToFile(String line) {
        if (metricsFile == null || metricsFile.isBlank()) {
            return;
        }
        try {
            Files.write(Path.of(metricsFile), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("CDC-METRIC-FILE-ERROR file={} error={}", metricsFile, e.toString());
        }
    }
}

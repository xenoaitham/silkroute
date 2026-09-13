package com.mapleretail.silkroute.batch.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The exact pipeline-metrics contract shape (E-019 / ADR-0006 decision 4):
 * {"metric":"<name>","value":<N>,"pipeline":"batch"} — ONLY these three keys,
 * value in whole seconds. Printed to stdout AND appended to ${METRICS_FILE}
 * when set (evidence artifact).
 */
public final class MetricsEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsEmitter.class);

    private MetricsEmitter() {
    }

    public static String metricLine(String metric, long value, String pipeline) {
        return "{\"metric\":\"" + metric + "\",\"value\":" + value + ",\"pipeline\":\"" + pipeline + "\"}";
    }

    public static void emit(String metric, long valueSeconds, String pipeline, String metricsFile) {
        String line = metricLine(metric, valueSeconds, pipeline);
        LOG.info("BATCH-METRIC {}", line);
        System.out.println(line);
        System.out.flush();
        append(metricsFile, line);
    }

    public static void append(String metricsFile, String line) {
        if (metricsFile == null || metricsFile.isBlank()) {
            return;
        }
        try {
            Files.write(Path.of(metricsFile), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("BATCH-METRIC-FILE-ERROR file={} error={}", metricsFile, e.toString());
        }
    }
}

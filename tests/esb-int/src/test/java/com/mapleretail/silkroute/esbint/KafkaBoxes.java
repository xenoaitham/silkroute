package com.mapleretail.silkroute.esbint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kafka access for the suite, via {@code docker exec sim-kafka} (the compose
 * container from docker-compose.yml, apache/kafka:3.8.0 with in-container CLI
 * at /opt/kafka/bin). DOCUMENTED CHOICE per the contract: docker exec instead
 * of a kafka-clients dependency — the Maven mirror is flaky and the container
 * already ships a working client. Topics are created --if-not-exists; reads
 * are `kafka-console-consumer --from-beginning --timeout-ms ...` passes (exit
 * code 0 on timeout), so every pass sees ALL records ever written to the
 * topic and the suite filters by its own unique run tokens.
 */
final class KafkaBoxes {

    private static final String CONTAINER = "sim-kafka";
    private static final String BOOTSTRAP = "localhost:9092"; // inside the container

    private KafkaBoxes() {
    }

    /** Ensure both contract topics exist (idempotent). */
    static void ensureTopics(String... topics) {
        for (String topic : topics) {
            exec("/opt/kafka/bin/kafka-topics.sh --bootstrap-server " + BOOTSTRAP
                    + " --create --if-not-exists --topic " + topic
                    + " --partitions 1 --replication-factor 1");
            Transcript.log("kafka: topic %s ensured", topic);
        }
    }

    /** One full pass over the topic (from the beginning); returns every record. */
    static List<String> consumeAll(String topic) {
        String stdout = exec("/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "
                + BOOTSTRAP + " --topic " + topic + " --from-beginning --timeout-ms 5000");
        List<String> records = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                records.add(trimmed);
            }
        }
        return records;
    }

    /** Repeated consume passes until a record containing {@code token} shows
     * up (async producers may lag the HTTP response) or the budget expires.
     * Returns the full record text, or null on timeout (the caller decides
     * whether absence is a failure). */
    static String waitForRecord(String topic, String token, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<String> last = List.of();
        do {
            last = consumeAll(topic);
            for (String record : last) {
                if (record.contains(token)) {
                    return record;
                }
            }
            sleep(1000);
        } while (System.nanoTime() < deadline);
        Transcript.log("kafka: token %s NOT found in %s (%d records scanned on last pass)",
                token, topic, last.size());
        return null;
    }

    /** Run a kafka CLI command inside sim-kafka; hard-fail with an actionable
     * message when the container is missing. */
    private static String exec(String command) {
        ProcessBuilder pb = new ProcessBuilder("docker", "exec", CONTAINER, "bash", "-c", command);
        pb.redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot run docker (is docker installed?)", e);
        }
        // Drain stdout to EOF BEFORE waitFor (prevents pipe-buffer deadlock when
        // the topic holds many records; EOF arrives when the process exits).
        String stdout;
        try {
            stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            p.destroyForcibly();
            throw new IllegalStateException("cannot read docker exec output", e);
        }
        boolean done;
        try {
            done = p.waitFor(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IllegalStateException("docker exec interrupted", e);
        }
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("docker exec " + CONTAINER + " timed out: " + command);
        }
        if (p.exitValue() != 0) {
            String stderr;
            try {
                stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                stderr = "(unreadable)";
            }
            throw new IllegalStateException("docker exec " + CONTAINER + " failed (exit "
                    + p.exitValue() + "): " + command
                    + "\nstderr: " + stderr
                    + "\n— is the sim network up? run: make up");
        }
        return stdout;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

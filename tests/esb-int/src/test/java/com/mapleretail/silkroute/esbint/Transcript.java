package com.mapleretail.silkroute.esbint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Suite transcript writer: every scenario logs its assertions and MEASURED
 * numbers (attempt counts, circuit-open latency, DLQ hits) to
 * {@code tests/esb-int/target/suite-transcript.log} so the orchestrator can
 * copy it into evidence/. Measurements are never fabricated — each line is
 * written next to the code that produced the number.
 */
final class Transcript {

    private static final Path FILE =
            Paths.get("target", "suite-transcript.log").toAbsolutePath();

    private Transcript() {
    }

    static synchronized void log(String format, Object... args) {
        String line = "[esb-int] " + (args.length == 0 ? format : String.format(format, args));
        System.out.println(line);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[esb-int] WARN cannot write transcript " + FILE + ": " + e);
        }
    }

    static Path file() {
        return FILE;
    }
}

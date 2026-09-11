package com.mapleretail.silkroute.esbint;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * System lifecycle for the ESB integration suite (the proven falsifiable boot
 * pattern from tests/contract LegacyErpTestApp, doubled for two apps).
 *
 * Default mode boots, in order:
 *   1. legacy ERP jar on 18080 (SERVER_PORT=18080, ERP_DEMO_GENERATE_ORDERS=0),
 *      waits for /actuator/health UP and the deterministic seed line
 *      "ERP SEED: skus=50 stores=8 stockRows=400";
 *   2. the toxiproxy "erp" proxy (18180 -> 18080) via the REST API;
 *   3. the ESB jar on 18081 with ESB_FAULT_INJECTION=true and ERP_BASEURL left
 *      at its (contract-pinned) default = through the proxy; waits for
 *      18082 /actuator/health UP.
 *
 * A missing jar or a boot failure FAILS the suite with build instructions —
 * never a skip. PIDs are recorded to target/*.pid and teardown destroys the
 * recorded Process handles (@AfterAll + shutdown hook) — NEVER pkill -f.
 * External overrides: ESB_TEST_BASEURL / ERP_TEST_BASEURL.
 * Opt-in diagnostics: ESBINT_HARNESS_ONLY=true boots and verifies only the
 * harness (ERP + toxiproxy + kafka) and skips the ESB.
 */
final class Apps {

    static final String ERP_JAR =
            Paths.get("apps", "legacy-erp", "target", "legacy-erp-1.0.0-SNAPSHOT.jar").toString();
    static final String ESB_JAR =
            Paths.get("apps", "esb", "target", "esb-1.0.0-SNAPSHOT.jar").toString();

    static final String ERP_SEED_MARKER = "ERP SEED:";
    static final String ERP_SEED_EXPECTED = "ERP SEED: skus=50 stores=8 stockRows=400";

    private static final Object LOCK = new Object();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private static Process erpProcess;
    private static Process esbProcess;
    private static Path erpLog;
    private static Path esbLog;
    private static Path erpPidFile;
    private static Path esbPidFile;
    private static Thread shutdownHook;

    private Apps() {
    }

    static boolean harnessOnly() {
        String raw = firstNonBlank(System.getProperty("esbint.harness.only"),
                System.getenv("ESBINT_HARNESS_ONLY"));
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    /** @BeforeAll entry: verify environment, then boot everything. */
    static void startAll() {
        synchronized (LOCK) {
            boolean harnessOnly = harnessOnly();
            Transcript.log("mode: %s", harnessOnly ? "HARNESS-ONLY (opt-in diagnostics; ESB scenarios are skipped)" : "FULL (ERP + toxiproxy + kafka + ESB)");

            checkInfraUp();
            // 18180 is deliberately NOT checked: it is toxiproxy infrastructure
            // (proxy "erp" on the host-network sim-esb-toxiproxy), not a process
            // this suite boots. The suite's own apps bind 18080/18081/18082.
            checkPortsFree(Wire.ERP_PORT, Wire.ESB_PORT, Wire.ESB_MGMT_PORT);

            bootErp();
            Toxi.ensureProxy();
            KafkaBoxes.ensureTopics(Wire.TOPIC_DLQ, Wire.TOPIC_EVENTS);
            if (!harnessOnly) {
                bootEsb();
            }
            Transcript.log("target dir for orchestrator evidence: %s",
                    Paths.get("target").toAbsolutePath());
        }
    }

    /** @AfterAll entry: stop the processes this suite launched. */
    static void stopAll() {
        synchronized (LOCK) {
            Toxi.cleanup();
            if (shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                shutdownHook = null;
            }
            destroy("esb", esbProcess, esbPidFile);
            destroy("erp", erpProcess, erpPidFile);
            esbProcess = null;
            erpProcess = null;
        }
    }

    // ------------------------------------------------------------------ boot

    private static void bootErp() {
        String external = firstNonBlank(System.getProperty("erp.baseurl"),
                System.getenv("ERP_TEST_BASEURL"));
        if (external != null) {
            Transcript.log("erp: external mode, testing already-running ERP at %s", external);
            awaitHealthy("http://127.0.0.1:" + Wire.ERP_PORT + "/actuator/health", "external ERP");
            return;
        }
        String jar = requireJar(ERP_JAR, "apps/legacy-erp");
        erpLog = Paths.get("target", "esb-int-erp.log").toAbsolutePath();
        erpPidFile = Paths.get("target", "erp-18080.pid").toAbsolutePath();

        Map<String, String> env = new HashMap<>();
        env.put("SERVER_PORT", String.valueOf(Wire.ERP_PORT));
        env.put("ERP_DEMO_GENERATE_ORDERS", "0");

        erpProcess = launch("erp", jar, env, erpLog, erpPidFile);
        registerShutdownHook();
        awaitHealthy(Wire.ERP_HEALTH, "booted ERP on " + Wire.ERP_PORT);
        verifySeedLine();
        Transcript.log("erp: healthy on %d, seed verified, pid=%d (file %s)",
                Wire.ERP_PORT, erpProcess.pid(), erpPidFile);
    }

    private static void bootEsb() {
        String external = firstNonBlank(System.getProperty("esb.baseurl"),
                System.getenv("ESB_TEST_BASEURL"));
        if (external != null) {
            Transcript.log("esb: external mode, testing already-running ESB at %s", external);
            awaitHealthy(Wire.ESB_HEALTH, "external ESB");
            return;
        }
        String jar = requireJar(ESB_JAR, "apps/esb");
        esbLog = Paths.get("target", "esb-int-esb.log").toAbsolutePath();
        esbPidFile = Paths.get("target", "esb-18081.pid").toAbsolutePath();

        Map<String, String> env = new HashMap<>();
        // Contract: boot with the fault-injection hook ON; ERP_BASEURL stays at
        // its default (through the toxiproxy erp listener on 18180).
        env.put("ESB_FAULT_INJECTION", "true");

        esbProcess = launch("esb", jar, env, esbLog, esbPidFile);
        registerShutdownHook();
        awaitHealthy(Wire.ESB_HEALTH, "booted ESB on " + Wire.ESB_PORT);
        Transcript.log("esb: healthy rest=%d mgmt=%d, pid=%d (file %s)",
                Wire.ESB_PORT, Wire.ESB_MGMT_PORT, esbProcess.pid(), esbPidFile);
    }

    private static String requireJar(String relativePath, String module) {
        Path dir = Paths.get("").toAbsolutePath();
        Path resolved = null;
        while (dir != null) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                resolved = candidate;
                break;
            }
            dir = dir.getParent();
        }
        if (resolved == null) {
            throw new IllegalStateException("fat jar not found: " + relativePath
                    + "\nBuild it first, then re-run the suite:\n"
                    + "  ./mvnw -B -f apps/" + module + "/pom.xml package -DskipTests\n"
                    + "  ./mvnw -B -f tests/esb-int/pom.xml verify");
        }
        return resolved.toString();
    }

    private static Process launch(String name, String jar, Map<String, String> env,
                                  Path log, Path pidFile) {
        ProcessBuilder builder = new ProcessBuilder("java", "-jar", jar);
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        try {
            Files.createDirectories(log.getParent());
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Process process = builder.start();
            Files.writeString(pidFile, process.pid() + System.lineSeparator());
            Transcript.log("%s: launched java -jar %s (env %s), pid=%d, log=%s",
                    name, jar, env, process.pid(), log);
            return process;
        } catch (IOException e) {
            throw new IllegalStateException("failed to launch: java -jar " + jar, e);
        }
    }

    private static void registerShutdownHook() {
        if (shutdownHook == null) {
            shutdownHook = new Thread(Apps::destroyNowForShutdown, "esb-int-app-reaper");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    // ------------------------------------------------------- falsifiable fail

    /** Ports the suite will BOOT on must be free — fail fast with clarity. */
    private static void checkPortsFree(int... ports) {
        for (int port : ports) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                throw new IllegalStateException("port " + port
                        + " is already in use — the suite refuses to boot over a live process."
                        + " Free the port (check target/*.pid, /tmp/silkroute-esb-*.pid, or"
                        + " `ss -ltnp sport = :" + port + "`) and re-run.");
            } catch (IllegalStateException e) {
                throw e;
            } catch (IOException expected) {
                // nothing listening — good
            }
        }
    }

    /** Sim infra must already be up (containers); fail with the fix. */
    private static void checkInfraUp() {
        // toxiproxy: API must answer
        try {
            Wire.Resp version = Wire.get("http://127.0.0.1:" + Wire.TOXIPROXY_API_PORT + "/version");
            if (version.status() != 200) {
                throw new IllegalStateException("toxiproxy API HTTP " + version.status());
            }
            Transcript.log("infra: toxiproxy %s", version.body().trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("toxiproxy API not reachable at 127.0.0.1:"
                    + Wire.TOXIPROXY_API_PORT + " (" + e.getMessage() + ") — run: make up");
        }
        for (int port : new int[]{Wire.KAFKA_PORT, Wire.REDIS_PORT}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            } catch (IOException e) {
                throw new IllegalStateException("required sim service not listening on 127.0.0.1:"
                        + port + " — run: make up");
            }
        }
        Transcript.log("infra: kafka:%d and redis:%d reachable", Wire.KAFKA_PORT, Wire.REDIS_PORT);
    }

    private static void awaitHealthy(String url, String what) {
        long timeoutSeconds = bootTimeoutSeconds();
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (isHealthy(url)) {
                return;
            }
            sleep(500);
        }
        throw new IllegalStateException(what + " did not report healthy at " + url
                + " within " + timeoutSeconds + "s.\n" + logTails());
    }

    private static long bootTimeoutSeconds() {
        String raw = firstNonBlank(System.getProperty("esbint.boot.timeout.seconds"),
                System.getenv("ESBINT_BOOT_TIMEOUT_SECONDS"));
        return raw == null ? 120 : Math.max(10, Long.parseLong(raw.trim()));
    }

    private static boolean isHealthy(String url) {
        try {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("UP");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void verifySeedLine() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String seed = findSeedLine();
            if (seed != null) {
                if (!ERP_SEED_EXPECTED.equals(seed)) {
                    throw new IllegalStateException("deterministic estate violated: expected '"
                            + ERP_SEED_EXPECTED + "' but ERP logged '" + seed + "'");
                }
                return;
            }
            sleep(500);
        }
        throw new IllegalStateException("ERP healthy but seed line '" + ERP_SEED_EXPECTED
                + "' not found in " + erpLog + "\n" + logTails());
    }

    private static String findSeedLine() {
        if (erpLog == null || !Files.isRegularFile(erpLog)) {
            return null;
        }
        try (Stream<String> lines = Files.lines(erpLog, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.contains(ERP_SEED_MARKER))
                    .reduce((first, second) -> second)
                    .map(line -> line.substring(line.indexOf(ERP_SEED_MARKER)).trim())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String logTails() {
        StringBuilder sb = new StringBuilder("---- boot log tails ----\n");
        for (Path log : new Path[]{erpLog, esbLog}) {
            if (log == null || !Files.isRegularFile(log)) {
                continue;
            }
            sb.append("== ").append(log).append(" ==\n");
            try {
                List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
                int from = Math.max(0, all.size() - 25);
                all.subList(from, all.size()).forEach(line -> sb.append(line).append('\n'));
            } catch (IOException e) {
                sb.append("(unreadable: ").append(e).append(")\n");
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- teardown

    /** Kill by the recorded Process handle / PID — NEVER pkill -f. */
    private static void destroy(String name, Process process, Path pidFile) {
        if (pidFile != null && Files.exists(pidFile)) {
            try {
                Files.deleteIfExists(pidFile);
            } catch (IOException ignored) {
                // best effort
            }
        }
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                Transcript.log("%s: did not exit gracefully, forcing", name);
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        Transcript.log("%s: stopped (pid %d)", name, process.pid());
    }

    private static void destroyNowForShutdown() {
        Process esb = esbProcess;
        Process erp = erpProcess;
        if (esb != null) {
            esb.destroy();
        }
        if (erp != null) {
            erp.destroy();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}

package com.mapleretail.silkroute.contract;

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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * App lifecycle for the legacy ERP SOAP contract suite.
 *
 * <p>Two modes, both loud on failure (a contract suite that silently passes
 * without a running service is worthless):
 *
 * <ul>
 *   <li><b>Boot mode</b> (default): locate the packaged fat jar
 *       {@code apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar}, launch it via
 *       ProcessBuilder with {@code SERVER_PORT} (default 18090, override with
 *       {@code ERP_TEST_PORT}), poll {@code /actuator/health}, verify the
 *       deterministic seed line, and hand the base URL to Karate via the
 *       {@code erp.baseurl} system property. {@link #destroy()} tears the
 *       process down (called from the JUnit {@code @AfterAll}). A missing jar
 *       or a boot failure FAILS the suite with instructions — never a skip.</li>
 *   <li><b>External mode</b>: if {@code ERP_TEST_BASEURL} (or system property
 *       {@code erp.baseurl}) is set, no process is launched and the suite tests
 *       the URL as-is. Unreachable URL (e.g. a dead port) => health-poll
 *       timeout => suite FAILS. This is also the falsifiability lever.</li>
 * </ul>
 */
public final class LegacyErpTestApp {

    /** Match the jar coordinate in apps/legacy-erp/pom.xml. */
    static final String JAR_RELATIVE_PATH =
            Paths.get("apps", "legacy-erp", "target", "legacy-erp-1.0.0-SNAPSHOT.jar").toString();

    static final String DEFAULT_PORT = "18090";
    static final String SEED_LOG_MARKER = "ERP SEED:";
    static final String EXPECTED_SEED = "ERP SEED: skus=50 stores=8 stockRows=400";
    static final int LOG_TAIL_LINES = 40;

    private static final Object LOCK = new Object();
    private static Process process;
    private static Thread shutdownHook;
    private static Path appLog;
    private static String baseUrl;

    private LegacyErpTestApp() {
    }

    /** Idempotent: boots once per JVM; every test class may call it. */
    public static void ensureStarted() {
        synchronized (LOCK) {
            String external = firstNonBlank(
                    System.getProperty("erp.baseurl"), System.getenv("ERP_TEST_BASEURL"));
            if (external != null) {
                baseUrl = stripTrailingSlash(external);
                System.setProperty("erp.baseurl", baseUrl);
                System.out.println("[contract-tests] external mode: testing already-running app at " + baseUrl);
                awaitHealthy(baseUrl, "external app at " + baseUrl);
                return;
            }
            if (baseUrl != null && process != null && process.isAlive() && isHealthy(baseUrl)) {
                return;
            }
            baseUrl = boot();
            System.setProperty("erp.baseurl", baseUrl);
        }
    }

    /** JUnit @AfterAll hook: stop the process this suite launched. */
    public static void destroy() {
        synchronized (LOCK) {
            if (shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                shutdownHook = null;
            }
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        System.out.println("[contract-tests] app did not exit gracefully, forcing");
                        process.destroyForcibly();
                        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                System.out.println("[contract-tests] app process stopped");
                process = null;
                baseUrl = null;
            }
        }
    }

    // ---------------------------------------------------------------- boot

    private static String boot() {
        String port = firstNonBlank(
                System.getProperty("erp.test.port"), System.getenv("ERP_TEST_PORT"), DEFAULT_PORT);
        String jar = firstNonBlank(System.getProperty("erp.jar"), System.getenv("ERP_JAR"), locateJar());

        if (jar == null || !Files.isRegularFile(Paths.get(jar))) {
            throw new IllegalStateException(
                    "legacy-erp fat jar not found at '" + (jar == null ? JAR_RELATIVE_PATH : jar) + "'.\n"
                    + "Build it first (do NOT skip this step, and do NOT silently skip the suite):\n"
                    + "  cd <repo-root> && ./mvnw -B -f apps/legacy-erp/pom.xml package -DskipTests\n"
                    + "then re-run the contract suite: ./mvnw -B -f tests/contract/pom.xml verify");
        }

        String url = "http://127.0.0.1:" + port;
        requirePortFree(Integer.parseInt(port));

        appLog = Paths.get("target", "legacy-erp-contract-app.log").toAbsolutePath();
        try {
            Files.createDirectories(appLog.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("cannot create app log directory " + appLog.getParent(), e);
        }

        System.out.println("[contract-tests] booting " + jar + " on " + url + " (log: " + appLog + ")");
        ProcessBuilder builder = new ProcessBuilder("java", "-jar", jar);
        builder.environment().put("SERVER_PORT", port);
        // ERP_WSS_USERNAME / ERP_WSS_PASSWORD are inherited as-is so the app and
        // this suite always read the same sim credentials.
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(appLog.toFile()));
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to launch: java -jar " + jar, e);
        }
        shutdownHook = new Thread(LegacyErpTestApp::destroyNowForShutdown, "legacy-erp-contract-app-reaper");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        awaitHealthy(url, "booted app on " + url);
        verifySeedLine();
        System.out.println("[contract-tests] app is healthy at " + url);
        return url;
    }

    /** Walk up from the working directory until the jar path resolves. */
    private static String locateJar() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(JAR_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static void requirePortFree(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            throw new IllegalStateException(
                    "port " + port + " is already in use — refusing to start a second app instance. "
                    + "Free the port or choose another with ERP_TEST_PORT=<port>.");
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException expected) {
            // nothing listening — good
        }
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
        // Falsifiable failure path: dump the app log tail so the cause is visible.
        throw new IllegalStateException(
                what + " did not report healthy at " + url + "/actuator/health within " + timeoutSeconds
                + "s.\n" + logTail());
    }

    private static long bootTimeoutSeconds() {
        String raw = firstNonBlank(
                System.getProperty("erp.test.boot.timeout.seconds"),
                System.getenv("ERP_TEST_BOOT_TIMEOUT_SECONDS"));
        return raw == null ? 60 : Math.max(5, Long.parseLong(raw.trim()));
    }

    private static boolean isHealthy(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && response.body().contains("UP");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static void verifySeedLine() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String seed = findSeedLine();
            if (seed != null) {
                if (!EXPECTED_SEED.equals(seed)) {
                    throw new IllegalStateException(
                            "deterministic estate violated: expected seed line '" + EXPECTED_SEED
                            + "' but app logged '" + seed + "'");
                }
                System.out.println("[contract-tests] seed verified: " + seed);
                return;
            }
            sleep(500);
        }
        throw new IllegalStateException(
                "app is healthy but the seed log line was not found; expected '" + EXPECTED_SEED + "'.\n" + logTail());
    }

    private static String findSeedLine() {
        if (appLog == null || !Files.isRegularFile(appLog)) {
            return null;
        }
        try (Stream<String> lines = Files.lines(appLog, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.contains(SEED_LOG_MARKER))
                    .reduce((first, second) -> second)
                    .map(line -> line.substring(line.indexOf(SEED_LOG_MARKER) + SEED_LOG_MARKER.length()).trim())
                    .map(line -> SEED_LOG_MARKER + " " + line)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String logTail() {
        if (appLog == null || !Files.isRegularFile(appLog)) {
            return "(no app log available)";
        }
        List<String> all = new ArrayList<>();
        try (Stream<String> stream = Files.lines(appLog, StandardCharsets.UTF_8)) {
            stream.forEach(all::add);
        } catch (IOException e) {
            return "(app log unreadable: " + e + ")";
        }
        int from = Math.max(0, all.size() - LOG_TAIL_LINES);
        List<String> lines = all.subList(from, all.size());
        return "---- app log tail (" + lines.size() + " lines) ----\n" + String.join("\n", lines);
    }

    private static void destroyNowForShutdown() {
        Process current = process;
        if (current != null) {
            current.destroy();
        }
    }

    // ---------------------------------------------------------------- misc

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

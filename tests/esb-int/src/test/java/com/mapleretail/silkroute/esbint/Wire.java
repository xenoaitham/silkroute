package com.mapleretail.silkroute.esbint;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Frozen Phase 2 contract constants (pinned by the orchestrator) + the tiny
 * HTTP/JSON plumbing every scenario shares. ALL endpoints are 127.0.0.1.
 */
final class Wire {

    // ---- ports / endpoints (contract, all 127.0.0.1) ------------------------
    static final int ERP_PORT = 18080;          // legacy ERP REST+SOAP
    static final int ESB_PORT = 18081;          // ESB REST: POST /api/v1/orders
    static final int ESB_MGMT_PORT = 18082;     // ESB /actuator/health
    static final int PROXY_PORT = 18180;        // toxiproxy listen -> ERP
    static final int TOXIPROXY_API_PORT = 18474; // host-network sim-esb-toxiproxy (the bridge-mode sim-toxiproxy cannot upstream to host loopback)
    static final int KAFKA_PORT = 39092;        // container sim-kafka host listener
    static final int REDIS_PORT = 16379;        // container sim-redis (ESB idempotency)

    static final String ESB_BASE = "http://127.0.0.1:" + ESB_PORT;
    static final String ESB_HEALTH = "http://127.0.0.1:" + ESB_MGMT_PORT + "/actuator/health";
    static final String ERP_BASE = "http://127.0.0.1:" + ERP_PORT;
    static final String ERP_HEALTH = ERP_BASE + "/actuator/health";
    static final String ORDERS_URL = ESB_BASE + "/api/v1/orders";

    // ---- topics (created idempotently by the suite via docker exec sim-kafka)
    static final String TOPIC_DLQ = "silkroute.esb.dlq";
    static final String TOPIC_EVENTS = "silkroute.orders.events";

    // ---- request template constants ----------------------------------------
    static final String SOURCE_SYSTEM = "ESB-INT-TEST";

    // ---- frozen money math (C5): CAD base, SGD x0.98 / CNY x5.13, HALF_UP.
    // Verified live against the ERP at build time; scenarios re-verify the unit
    // price at runtime via the ERP pricing SOAP op and pin this SG example.
    static final int EXPECTED_SG_SKU0002_Q2_MINOR = 1248; // 2 x HALF_UP(637x0.98)=2x624

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Wire() {
    }

    /** status + body + measured elapsed of one HTTP exchange. */
    record Resp(int status, String body, long elapsedMillis) {
        JsonNode json() {
            try {
                return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
            } catch (IOException e) {
                throw new IllegalStateException("response is not JSON: " + body, e);
            }
        }
    }

    static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("not JSON: " + json, e);
        }
    }

    static Resp get(String url) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException e) {
            throw new IllegalStateException("GET " + url + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GET " + url + " interrupted", e);
        }
    }

    static Resp post(String url, String body, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("headers must be name/value pairs");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException e) {
            throw new IllegalStateException("POST " + url + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("POST " + url + " interrupted", e);
        }
    }

    // ------------------------------------------------------------------ order

    /** Body exactly per the frozen Phase 2 contract. tok is unique per order. */
    static String orderBody(String storeId, String skuId, int quantity, String customerRef, String tok) {
        return "{" +
                "\"externalOrderRef\":\"ESBINT-" + tok + "\"," +
                "\"sourceSystem\":\"" + SOURCE_SYSTEM + "\"," +
                "\"storeId\":\"" + storeId + "\"," +
                "\"channel\":\"WEB_STORE\"," +
                "\"customerRef\":\"" + customerRef + "\"," +
                "\"lines\":[{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}]," +
                "\"audit\":{\"sourceSystem\":\"" + SOURCE_SYSTEM + "\"," +
                "\"receivedAt\":\"" + java.time.Instant.now() + "\"," +
                "\"correlationId\":\"corr-" + tok + "\"}}";
    }

    /** POST one order; Idempotency-Key defaults to the correlationId. */
    static Resp postOrder(String storeId, String skuId, int quantity, String customerRef,
                          String tok, String faultInjectionHeader) {
        String corr = "corr-" + tok;
        return faultInjectionHeader == null
                ? post(ORDERS_URL, orderBody(storeId, skuId, quantity, customerRef, tok),
                        "Idempotency-Key", corr)
                : post(ORDERS_URL, orderBody(storeId, skuId, quantity, customerRef, tok),
                        "Idempotency-Key", corr, "X-Fault-Injection", faultInjectionHeader);
    }

    // ------------------------------------------------------------- json utils

    /** Deep search for every value stored under {@code key}; string values that
     * themselves parse as JSON are descended into (double-encoded payloads). */
    static java.util.List<JsonNode> findAllByKey(JsonNode node, String key) {
        java.util.List<JsonNode> hits = new java.util.ArrayList<>();
        collect(node, key, hits, 0);
        return hits;
    }

    private static void collect(JsonNode node, String key, java.util.List<JsonNode> hits, int depth) {
        if (node == null || depth > 8) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getKey().equals(key)) {
                    hits.add(e.getValue());
                }
                collect(e.getValue(), key, hits, depth + 1);
            });
        } else if (node.isArray()) {
            node.forEach(c -> collect(c, key, hits, depth + 1));
        } else if (node.isTextual() && depth > 0 && node.asText().trim().startsWith("{")) {
            try {
                collect(MAPPER.readTree(node.asText()), key, hits, depth + 1);
            } catch (IOException ignored) {
                // a plain string that merely looks like JSON — not a payload
            }
        }
    }

    /** maxAttempts across an attempts object: values are ints; steps that were
     * never reached legitimately record 0 (e.g. an order-step exhaustion never
     * touches reserve/pricing/confirm) — the max over REACHED steps is the
     * retry signal under test. */
    static int maxAttempts(JsonNode attempts) {
        if (attempts == null || !attempts.isObject() || attempts.isEmpty()) {
            throw new AssertionError("attempts object missing or empty: " + attempts);
        }
        int max = 0;
        for (var it = attempts.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode v = e.getValue();
            if (!v.canConvertToInt() || v.asInt() < 0) {
                throw new AssertionError("attempts." + e.getKey() + " is not an integer >= 0: " + v);
            }
            max = Math.max(max, v.asInt());
        }
        return max;
    }
}

package com.mapleretail.silkroute.esbint;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Toxiproxy REST client (http://127.0.0.1:8474, container sim-toxiproxy) for
 * the proxy named "erp": listen 127.0.0.1:18180 -> upstream 127.0.0.1:18080
 * (both real host-loopback binds — the API toxiproxy, sim-esb-toxiproxy on
 * 18474, runs with network_mode: host precisely so the upstream reaches the
 * host-bound ERP jar; the bridge-mode sim-toxiproxy cannot).
 * ALL ERP traffic from the ESB flows through it. Every call is against the
 * public toxiproxy v2 API, pure JSON — identical to what tests/chaos/esb-faults.sh
 * does from the shell.
 */
final class Toxi {

    static final String API = "http://127.0.0.1:" + Wire.TOXIPROXY_API_PORT;
    static final String PROXY_NAME = "erp";
    static final String LISTEN = "127.0.0.1:" + Wire.PROXY_PORT;
    static final String UPSTREAM = "127.0.0.1:" + Wire.ERP_PORT;

    private Toxi() {
    }

    /** Idempotently ensure the erp proxy exists and is enabled. */
    static void ensureProxy() {
        Wire.Resp get = Wire.get(API + "/proxies/" + PROXY_NAME);
        if (get.status() == 404) {
            Wire.Resp created = Wire.post(API + "/proxies", "{\"name\":\"" + PROXY_NAME
                    + "\",\"listen\":\"" + LISTEN + "\",\"upstream\":\"" + UPSTREAM
                    + "\",\"enabled\":true}");
            if (created.status() != 201 && created.status() != 200) {
                throw new IllegalStateException("cannot create toxiproxy proxy '" + PROXY_NAME
                        + "' (" + created.status() + "): " + created.body()
                        + " — is the sim network up? run: make up");
            }
            Transcript.log("toxiproxy: created proxy %s %s -> %s", PROXY_NAME, LISTEN, UPSTREAM);
        } else if (get.status() != 200) {
            throw new IllegalStateException("toxiproxy API unreachable at " + API + " (HTTP "
                    + get.status() + ") — is the sim network up? run: make up");
        } else {
            JsonNode proxy = get.json();
            if (!UPSTREAM.equals(proxy.path("upstream").asText())) {
                throw new IllegalStateException("toxiproxy proxy '" + PROXY_NAME + "' points at '"
                        + proxy.path("upstream").asText() + "' but the contract pins " + UPSTREAM);
            }
        }
        setEnabled(true);
    }

    /** POST /proxies/erp {"enabled":bool} — the hard-down / heal switch. */
    static void setEnabled(boolean enabled) {
        Wire.Resp r = Wire.post(API + "/proxies/" + PROXY_NAME, "{\"enabled\":" + enabled + "}");
        if (r.status() != 200) {
            throw new IllegalStateException("cannot " + (enabled ? "enable" : "disable")
                    + " proxy " + PROXY_NAME + " (" + r.status() + "): " + r.body());
        }
        Transcript.log("toxiproxy: proxy %s enabled=%s", PROXY_NAME, enabled);
    }

    /** Idempotently add the contract's latency toxic (default 600 ms). */
    static void addLatency(long latencyMs) {
        removeToxic("lat");
        Wire.Resp r = Wire.post(API + "/proxies/" + PROXY_NAME + "/toxics",
                "{\"name\":\"lat\",\"type\":\"latency\",\"attributes\":{\"latency\":" + latencyMs + "}}");
        if (r.status() != 200) {
            throw new IllegalStateException("cannot add latency toxic (" + r.status() + "): " + r.body());
        }
        Transcript.log("toxiproxy: added latency toxic %d ms", latencyMs);
    }

    static void removeToxic(String name) {
        // DELETE /proxies/erp/toxics/<name> — 204 on success, 404 when absent.
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(API + "/proxies/" + PROXY_NAME + "/toxics/" + name))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .DELETE().build();
            java.net.http.HttpResponse<String> r =
                    java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 204 && r.statusCode() != 404) {
                throw new IllegalStateException("cannot delete toxic '" + name + "': HTTP " + r.statusCode());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("DELETE toxic failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DELETE toxic interrupted", e);
        }
    }

    /** Post-scenario hygiene: no toxics left, proxy enabled. */
    static void cleanup() {
        try {
            removeToxic("lat");
            setEnabled(true);
        } catch (RuntimeException e) {
            Transcript.log("toxiproxy: cleanup issue (ignored): %s", e);
        }
    }
}

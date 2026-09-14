package com.mapleretail.silkroute.cdc.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * One raw Debezium envelope (the value of a ChangeEvent on the CDC topic).
 * With schemas.enable=false the wire shape is exactly:
 *
 * {"before":{...}|null,"after":{...}|null,
 *  "source":{"version":"..","connector":"mysql","name":"<topic.prefix>",
 *            "ts_ms":<source commit ms>,"db":"silkroute_oms","table":"oms_order",...},
 *  "op":"c|r|u|d","ts_ms":<engine ms>,"transaction":null}
 *
 * The envelope is passed through VERBATIM to Kafka and bronze (write-once,
 * ADR-0006); this class only READS the fields needed for logging, routing
 * (region/table) and the freshness metric.
 */
public final class Envelope {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    private Envelope(JsonNode root) {
        this.root = root;
    }

    public static Envelope fromJson(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        if (node == null || !node.isObject()) {
            throw new IOException("envelope is not a JSON object");
        }
        return new Envelope(node);
    }

    public String op() {
        return text(root.get("op"));
    }

    public String table() {
        return text(path(path(root, "source"), "table"));
    }

    public String server() {
        return text(path(path(root, "source"), "name"));
    }

    /** Source-commit timestamp (ts_ms inside source) - the freshness contract input. */
    public Long sourceTsMs() {
        JsonNode n = path(path(root, "source"), "ts_ms");
        return n == null || !n.canConvertToLong() ? null : n.asLong();
    }

    /**
     * Region tag for the bronze key (C1): after.region for c/r/u, before.region
     * for deletes; empty string when neither is present (caller must fail fast
     * - never land an object without its region tag).
     */
    public String region() {
        String r = text(path(path(root, "after"), "region"));
        if (r.isEmpty()) {
            r = text(path(path(root, "before"), "region"));
        }
        return r;
    }

    public boolean hasAfter() {
        JsonNode a = root.get("after");
        return a != null && a.isObject();
    }

    public boolean hasBefore() {
        JsonNode b = root.get("before");
        return b != null && b.isObject();
    }

    /** Round-trip serialization of the untouched raw envelope. */
    public String toJson() {
        return root.toString();
    }

    public JsonNode raw() {
        return root;
    }

    private static JsonNode path(JsonNode node, String field) {
        return node == null ? null : node.get(field);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }
}

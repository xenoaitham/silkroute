package com.mapleretail.silkroute.cdc.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Shared helpers for both cdc mains: env indirection (ADR-0001: every knob is
 * ${VAR:default}) and UTC date math for the bronze dt= partition (C5:
 * timezone-explicit, never the JVM default zone).
 */
public final class Env {

    private static final DateTimeFormatter DT_UTC = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private Env() {
    }

    public static String get(String name, String def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            v = System.getProperty(name);
        }
        return v == null || v.isBlank() ? def : v.trim();
    }

    public static int getInt(String name, int def) {
        return Integer.parseInt(get(name, String.valueOf(def)));
    }

    public static long getLong(String name, long def) {
        return Long.parseLong(get(name, String.valueOf(def)));
    }

    /** Bronze dt partition: yyyyMMdd of the source-commit timestamp in UTC. */
    public static String dtUtc(long epochMs) {
        return DT_UTC.format(Instant.ofEpochMilli(epochMs));
    }
}

package com.mapleretail.silkroute.cdc.bronze;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.cdc.common.Env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bronze key format (ADR-0006): region/table/dt prefixes + unique object name. */
class BronzeKeysTest {

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^region=(CA|SG|CN)/table=oms_order(_line)?/dt=\\d{8}/silkroute\\.cdc\\.oms-\\d+-\\d+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jsonl$");

    @Test
    void keyMatchesTheAdrFormatExactly() {
        String key = BronzeKeys.objectKey("CA", "oms_order", "20260913", "silkroute.cdc.oms", 0, 42,
                "0b6c2ea1-5e2b-4d8a-9a55-1f2e3d4c5b6a");
        assertEquals("region=CA/table=oms_order/dt=20260913/silkroute.cdc.oms-0-42-0b6c2ea1-5e2b-4d8a-9a55-1f2e3d4c5b6a.jsonl", key);
        assertTrue(KEY_PATTERN.matcher(key).matches(), "key must match the ADR-0006 format: " + key);
    }

    @Test
    void lineTableKeysMatchToo() {
        String key = BronzeKeys.objectKey("CN", "oms_order_line", "20260913", "silkroute.cdc.oms", 0, 7, "x-y".replace('-', '0'));
        assertTrue(key.startsWith("region=CN/table=oms_order_line/dt=20260913/"));
    }

    @Test
    void dtFolderIsUtcDateOfSourceCommitTimestamp() {
        // derived from Instant (UTC by definition) instead of hand-computed constants
        long noonUtc = java.time.Instant.parse("2026-09-13T12:00:00Z").toEpochMilli();
        assertEquals("20260913", BronzeKeys.dtFolder(noonUtc));
        // 2026-09-12T23:59:59.999Z is still 0912 in UTC even if SGT is already the 13th
        long beforeMidnightUtc = java.time.Instant.parse("2026-09-12T23:59:59.999Z").toEpochMilli();
        assertEquals("20260912", BronzeKeys.dtFolder(beforeMidnightUtc));
        assertEquals("19700101", BronzeKeys.dtFolder(0L));
        assertEquals(Env.dtUtc(noonUtc), BronzeKeys.dtFolder(noonUtc));
    }
}

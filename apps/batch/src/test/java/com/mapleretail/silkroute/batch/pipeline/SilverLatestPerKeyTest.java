package com.mapleretail.silkroute.batch.pipeline;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Dedup-latest semantics: the pure reference used to pin the Spark Window expression. */
class SilverLatestPerKeyTest {

    record Envelope(String pk, long sourceTsMs, String bronzeObject, int value) {
    }

    @Test
    void keepsTheHighestSourceTimestampPerKey() {
        List<Envelope> out = SilverPipeline.latestPerKeyReference(
                List.of(
                        new Envelope("ORD-1", 100L, "obj-a", 1),
                        new Envelope("ORD-1", 300L, "obj-b", 3), // latest
                        new Envelope("ORD-1", 200L, "obj-c", 2),
                        new Envelope("ORD-2", 150L, "obj-d", 9)),
                Envelope::pk, e -> e.sourceTsMs(), e -> e.bronzeObject());
        assertEquals(2, out.size());
        assertEquals(3, out.stream().filter(e -> e.pk().equals("ORD-1")).findFirst().orElseThrow().value());
        assertEquals(9, out.stream().filter(e -> e.pk().equals("ORD-2")).findFirst().orElseThrow().value());
    }

    @Test
    void tiesBreakDeterministicallyByBronzeObjectDesc() {
        List<Envelope> out = SilverPipeline.latestPerKeyReference(
                List.of(
                        new Envelope("ORD-1", 300L, "obj-b", 1),
                        new Envelope("ORD-1", 300L, "obj-c", 2)), // same ts, "obj-c" > "obj-b" wins
                Envelope::pk, e -> e.sourceTsMs(), e -> e.bronzeObject());
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).value());
    }
}

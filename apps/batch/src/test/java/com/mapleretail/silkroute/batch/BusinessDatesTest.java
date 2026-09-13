package com.mapleretail.silkroute.batch;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessDatesTest {

    private static Clock at(String isoUtc) {
        return Clock.fixed(Instant.parse(isoUtc), ZoneId.of("UTC"));
    }

    @Test
    void yesterdayIsComputedInAsiaSingaporeNotTheJvmZone() {
        // 2026-09-13T20:00Z is 2026-09-14 04:00 SGT -> yesterday SGT = 2026-09-13
        // (in UTC it would already be the 13th; in SGT the "today" is the 14th)
        assertEquals("2026-09-13", BusinessDates.yesterdayAsiaSingapore(at("2026-09-13T20:00:00Z")));
        // 2026-09-13T00:30Z is 08:30 SGT on the 13th -> yesterday = the 12th
        assertEquals("2026-09-12", BusinessDates.yesterdayAsiaSingapore(at("2026-09-13T00:30:00Z")));
        // 2026-09-12T16:00Z is 2026-09-13 00:00 SGT exactly -> yesterday = the 12th
        assertEquals("2026-09-12", BusinessDates.yesterdayAsiaSingapore(at("2026-09-12T16:00:00Z")));
    }

    @Test
    void dtDigitsStripDashes() {
        assertEquals("20260913", BusinessDates.dtDigits("2026-09-13"));
    }

    @Test
    void malformedBusinessDateFailsLoudly() {
        assertThrows(Exception.class, () -> BusinessDates.parse("2026-9-13"));
        assertThrows(Exception.class, () -> BusinessDates.parse("not-a-date"));
    }
}

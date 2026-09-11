package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.esb.saga.Region;

/**
 * Region derivation (routing + C1 masking input) and C5 currency mapping.
 */
class RegionTest {

    @Test
    void derivesRegionFromStorePrefix() {
        assertEquals(Region.CA, Region.fromStoreId("ST-CA-01"));
        assertEquals(Region.CA, Region.fromStoreId("ST-CA-03"));
        assertEquals(Region.SG, Region.fromStoreId("ST-SG-02"));
        assertEquals(Region.CN, Region.fromStoreId("ST-CN-01"));
    }

    @Test
    void mapsRegionToCurrency() {
        assertEquals("CAD", Region.CA.currency());
        assertEquals("SGD", Region.SG.currency());
        assertEquals("CNY", Region.CN.currency());
    }

    @Test
    void rejectsUnknownRegions() {
        assertThrows(IllegalArgumentException.class, () -> Region.fromStoreId("ST-US-01"));
        assertThrows(IllegalArgumentException.class, () -> Region.fromStoreId(null));
        assertThrows(IllegalArgumentException.class, () -> Region.fromStoreId("STORE-1"));
        assertTrue(!Region.isValidStoreId("ST-XX-99"));
        assertTrue(Region.isValidStoreId("ST-CN-02"));
    }
}

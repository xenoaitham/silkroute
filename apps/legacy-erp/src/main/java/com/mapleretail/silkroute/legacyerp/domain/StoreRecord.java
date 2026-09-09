package com.mapleretail.silkroute.legacyerp.domain;

import com.mapleretail.silkroute.legacyerp.contract.common.StoreRegionCodeType;

/**
 * A physical store. {@code region} (CA/SG/CN) is load-bearing: it decides the
 * currency orders are priced in here and drives Phase 2 content-based routing —
 * keep it prominent in getStock responses.
 */
public record StoreRecord(String storeId, StoreRegionCodeType region) {
}

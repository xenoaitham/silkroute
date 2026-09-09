package com.mapleretail.silkroute.legacyerp.seed;

import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.legacyerp.contract.common.StoreRegionCodeType;
import com.mapleretail.silkroute.legacyerp.domain.StoreRecord;
import com.mapleretail.silkroute.legacyerp.domain.SkuRecord;
import com.mapleretail.silkroute.legacyerp.ledger.InventoryLedger;
import com.mapleretail.silkroute.legacyerp.ledger.SkuCatalog;
import com.mapleretail.silkroute.legacyerp.ledger.StoreDirectory;

/**
 * Deterministic boot-time seed: 50 SKUs, 8 stores (3x CA, 3x SG, 2x CN) and one
 * stock row per store x SKU. Stock quantities come from Random(42) so every boot
 * of the sim produces identical data. The "ERP SEED" log line is the evidence
 * record for the seed counts — keep its format greppable and stable.
 */
@Component
public class ErpSeedData {

    public static final Logger LOG = LoggerFactory.getLogger(ErpSeedData.class);

    public static final int SKU_COUNT = 50;
    public static final long SKU_BASE_PRICE_FLOOR_CAD_MINOR = 500L;
    public static final long SKU_BASE_PRICE_STRIDE_CAD_MINOR = 137L;
    public static final long SKU_BASE_PRICE_SPAN_CAD_MINOR = 9500L;
    public static final int DISCOUNT_ELIGIBLE_EVERY_NTH = 3;
    public static final int ON_HAND_FLOOR = 5;
    public static final int ON_HAND_SPAN = 96;
    public static final long STOCK_SEED = 42L;

    public static final String SKU_ID_FORMAT = "SKU-%04d";

    private static final List<String> CATEGORIES = List.of(
            "BEVERAGE", "SNACK", "HOUSEHOLD", "PERSONAL_CARE", "FRESH", "DRY_GOODS", "STATIONERY", "TOYS");

    private static final List<StoreRecord> STORE_FIXTURES = List.of(
            new StoreRecord("ST-CA-01", StoreRegionCodeType.CA),
            new StoreRecord("ST-CA-02", StoreRegionCodeType.CA),
            new StoreRecord("ST-CA-03", StoreRegionCodeType.CA),
            new StoreRecord("ST-SG-01", StoreRegionCodeType.SG),
            new StoreRecord("ST-SG-02", StoreRegionCodeType.SG),
            new StoreRecord("ST-SG-03", StoreRegionCodeType.SG),
            new StoreRecord("ST-CN-01", StoreRegionCodeType.CN),
            new StoreRecord("ST-CN-02", StoreRegionCodeType.CN));

    public ErpSeedData(SkuCatalog skuCatalog, StoreDirectory storeDirectory, InventoryLedger inventoryLedger) {
        STORE_FIXTURES.forEach(storeDirectory::register);
        seedSkuCatalog(skuCatalog);
        seedStock(skuCatalog, storeDirectory, inventoryLedger);
        LOG.info("ERP SEED: skus={} stores={} stockRows={}",
                skuCatalog.count(), storeDirectory.count(), inventoryLedger.stockRowCount());
    }

    private static void seedSkuCatalog(SkuCatalog skuCatalog) {
        for (int index = 0; index < SKU_COUNT; index++) {
            int oneBased = index + 1;
            String category = CATEGORIES.get(index % CATEGORIES.size());
            long basePriceCadMinor = SKU_BASE_PRICE_FLOOR_CAD_MINOR
                    + (index * SKU_BASE_PRICE_STRIDE_CAD_MINOR) % SKU_BASE_PRICE_SPAN_CAD_MINOR;
            skuCatalog.register(new SkuRecord(
                    SKU_ID_FORMAT.formatted(oneBased),
                    category + " Item " + "%03d".formatted(oneBased),
                    category,
                    basePriceCadMinor,
                    index % DISCOUNT_ELIGIBLE_EVERY_NTH == 0));
        }
    }

    private static void seedStock(SkuCatalog skuCatalog, StoreDirectory storeDirectory, InventoryLedger inventoryLedger) {
        List<SkuRecord> skus = skuCatalog.all();
        Random random = new Random(STOCK_SEED);
        for (StoreRecord store : storeDirectory.all()) {
            for (SkuRecord sku : skus) {
                inventoryLedger.seedRow(store.storeId(), sku.skuId(), ON_HAND_FLOOR + random.nextInt(ON_HAND_SPAN));
            }
        }
    }
}

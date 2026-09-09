package com.mapleretail.silkroute.legacyerp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.mapleretail.silkroute.legacyerp.ledger.InventoryLedger;
import com.mapleretail.silkroute.legacyerp.ledger.OrderLedger;
import com.mapleretail.silkroute.legacyerp.ledger.SkuCatalog;
import com.mapleretail.silkroute.legacyerp.ledger.StoreDirectory;
import com.mapleretail.silkroute.legacyerp.seed.ErpSeedData;

/**
 * Boots the full estate (Tomcat + CXFServlet + published endpoints) once to prove
 * the wiring: seed counts, ledger state, and the demo generator staying off by
 * default. SOAP-level behavior is verified out-of-band with curl against the
 * packaged jar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyErpApplicationTest {

    @Autowired
    private SkuCatalog skuCatalog;

    @Autowired
    private StoreDirectory storeDirectory;

    @Autowired
    private InventoryLedger inventoryLedger;

    @Autowired
    private OrderLedger orderLedger;

    @Test
    void contextBootsWithExpectedSeedCounts() {
        assertEquals(ErpSeedData.SKU_COUNT, skuCatalog.count());
        assertEquals(8, storeDirectory.count());
        assertEquals(ErpSeedData.SKU_COUNT * 8, inventoryLedger.stockRowCount());
        // Demo generator is off by default: no orders exist at boot.
        assertEquals(0, orderLedger.count());
        assertNotNull(skuCatalog.find("SKU-0001"));
        assertNotNull(skuCatalog.find("SKU-0050"));
        assertNull(skuCatalog.find("SKU-0051"));
    }
}

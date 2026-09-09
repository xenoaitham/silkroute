package com.mapleretail.silkroute.legacyerp.demo;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.legacyerp.contract.common.LegacyAuditType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderChannelType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderLineInputType;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest;
import com.mapleretail.silkroute.legacyerp.ledger.StoreDirectory;
import com.mapleretail.silkroute.legacyerp.seed.ErpSeedData;
import com.mapleretail.silkroute.legacyerp.service.OrderServiceImpl;
import com.mapleretail.silkroute.legacyerp.time.XmlDateTimes;

import maple.erp.orders.v1.InvalidOrderFault;

/**
 * Demo-data generator: when erp.demo.generate-orders > 0, submits that many
 * orders through the service layer ({@link OrderServiceImpl}, never the ledger
 * directly) so generated orders carry the same validation evidence as real ones.
 * Randomness is seeded (Random(42)) for reproducible runs; invalid combinations
 * are logged and skipped. The "ERP DEMO" summary line is the seed-count evidence.
 */
@Component
public class DemoOrderGenerator implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoOrderGenerator.class);

    public static final String SOURCE_SYSTEM = "demo-generator";
    public static final String EXTERNAL_REF_PREFIX = "DEMO-42-";
    public static final String CORRELATION_PREFIX = "demo-42-";
    public static final String ENTRY_CLERK = "demo-runner";
    public static final long RANDOM_SEED = 42L;

    public static final int MAX_LINES_PER_ORDER = 3;
    public static final int MAX_QUANTITY = 5;

    private final OrderServiceImpl orderService;
    private final StoreDirectory storeDirectory;
    private final int orderCount;

    public DemoOrderGenerator(OrderServiceImpl orderService,
                              StoreDirectory storeDirectory,
                              @org.springframework.beans.factory.annotation.Value("${erp.demo.generate-orders:0}")
                              int orderCount) {
        this.orderService = orderService;
        this.storeDirectory = storeDirectory;
        this.orderCount = orderCount;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (orderCount <= 0) {
            return;
        }
        Random random = new Random(RANDOM_SEED);
        List<String> storeIds = storeDirectory.all().stream().map(s -> s.storeId()).toList();
        int submitted = 0;
        int skipped = 0;
        for (int i = 1; i <= orderCount; i++) {
            try {
                orderService.submitOrder(buildOrder(i, storeIds, random));
                submitted++;
            } catch (InvalidOrderFault fault) {
                skipped++;
                LOG.warn("ERP DEMO: order {} rejected ({} {})", i, fault.getFaultInfo().getErrorCode(),
                        fault.getFaultInfo().getInvalidField());
            }
        }
        LOG.info("ERP DEMO: requested={} submitted={} skipped={}", orderCount, submitted, skipped);
    }

    private SubmitOrderRequest buildOrder(int ordinal, List<String> storeIds, Random random) {
        SubmitOrderRequest request = new SubmitOrderRequest();
        request.setExternalOrderRef(EXTERNAL_REF_PREFIX + "%06d".formatted(ordinal));
        request.setStoreId(storeIds.get(random.nextInt(storeIds.size())));
        request.setCustomerRef(null);
        request.setOrderChannel(OrderChannelType.values()[random.nextInt(OrderChannelType.values().length)]);

        int lineCount = 1 + random.nextInt(MAX_LINES_PER_ORDER);
        for (int i = 0; i < lineCount; i++) {
            OrderLineInputType line = new OrderLineInputType();
            line.setSkuId(ErpSeedData.SKU_ID_FORMAT.formatted(1 + random.nextInt(ErpSeedData.SKU_COUNT)));
            line.setQuantity(1 + random.nextInt(MAX_QUANTITY));
            request.getLines().add(line);
        }

        LegacyAuditType audit = new LegacyAuditType();
        audit.setSourceSystem(SOURCE_SYSTEM);
        audit.setReceivedAt(XmlDateTimes.utcTimestamp(Instant.now()));
        audit.setCorrelationId(CORRELATION_PREFIX + ordinal);
        audit.setEntryClerk(ENTRY_CLERK);
        request.setAudit(audit);
        return request;
    }
}

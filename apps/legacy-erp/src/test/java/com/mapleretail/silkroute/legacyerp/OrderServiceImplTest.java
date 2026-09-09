package com.mapleretail.silkroute.legacyerp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType;
import com.mapleretail.silkroute.legacyerp.contract.common.LegacyAuditType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderChannelType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderLineInputType;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusRequest;
import com.mapleretail.silkroute.legacyerp.fault.ErpFaults;
import com.mapleretail.silkroute.legacyerp.ledger.InventoryLedger;
import com.mapleretail.silkroute.legacyerp.ledger.OrderLedger;
import com.mapleretail.silkroute.legacyerp.ledger.SkuCatalog;
import com.mapleretail.silkroute.legacyerp.ledger.StoreDirectory;
import com.mapleretail.silkroute.legacyerp.seed.ErpSeedData;
import com.mapleretail.silkroute.legacyerp.service.OrderServiceImpl;
import com.mapleretail.silkroute.legacyerp.time.XmlDateTimes;

import maple.erp.orders.v1.InvalidOrderFault;

/**
 * Order validation and region-currency totals, against the seeded fixtures
 * (SKU-0001 base price is 500 CAD minor; SGD factor 0.98, CNY 5.13).
 */
class OrderServiceImplTest {

    private OrderServiceImpl orderService;

    @BeforeEach
    void seedFixtures() {
        SkuCatalog skuCatalog = new SkuCatalog();
        StoreDirectory storeDirectory = new StoreDirectory();
        OrderLedger orderLedger = new OrderLedger();
        new ErpSeedData(skuCatalog, storeDirectory, new InventoryLedger(java.time.Clock.systemUTC()));
        orderService = new OrderServiceImpl(storeDirectory, skuCatalog, orderLedger);
    }

    @Test
    void submitOrderPricesTotalInStoreRegionCurrency() throws InvalidOrderFault {
        SubmitOrderRequest request = validRequest("TEST-REF-000001");
        request.setStoreId("ST-SG-01");
        line(request, "SKU-0001", 2);

        var response = orderService.submitOrder(request);

        assertEquals("ORD-2026-000001", response.getOrderId());
        assertEquals("SUBMITTED", response.getStatus().name());
        assertEquals(CurrencyCodeType.SGD, response.getTotalAmount().getCurrency());
        // 2 x (500 CAD minor x 0.98 = 490 SGD minor) = 980 SGD minor.
        assertEquals(980L, response.getTotalAmount().getAmountMinor());
        assertEquals(1, response.getLineCount());
    }

    @Test
    void chineseStorePricesInCny() throws InvalidOrderFault {
        SubmitOrderRequest request = validRequest("TEST-REF-000002");
        request.setStoreId("ST-CN-01");
        line(request, "SKU-0001", 1);

        var response = orderService.submitOrder(request);

        assertEquals(CurrencyCodeType.CNY, response.getTotalAmount().getCurrency());
        // 500 CAD minor x 5.13 = 2565 CNY minor.
        assertEquals(2565L, response.getTotalAmount().getAmountMinor());
    }

    @Test
    void duplicateExternalRefPerSourceSystemFaults() throws InvalidOrderFault {
        SubmitOrderRequest first = validRequest("TEST-REF-DUP");
        line(first, "SKU-0001", 1);
        orderService.submitOrder(first);

        InvalidOrderFault fault = assertThrows(InvalidOrderFault.class, () -> {
            SubmitOrderRequest second = validRequest("TEST-REF-DUP");
            line(second, "SKU-0001", 1);
            orderService.submitOrder(second);
        });
        assertEquals(ErpFaults.CODE_ORDER_DUPLICATE_REF, fault.getFaultInfo().getErrorCode());
        assertEquals("externalOrderRef", fault.getFaultInfo().getInvalidField());
        assertEquals("TEST-REF-DUP", fault.getFaultInfo().getInvalidValue());
    }

    @Test
    void distinctValidationViolationsCarryDistinctErrorCodes() {
        InvalidOrderFault missingAudit = assertThrows(InvalidOrderFault.class, () -> {
            SubmitOrderRequest request = validRequest("REF-A");
            request.setAudit(null);
            orderService.submitOrder(request);
        });
        assertEquals(ErpFaults.CODE_ORDER_MISSING_AUDIT, missingAudit.getFaultInfo().getErrorCode());
        assertEquals("audit", missingAudit.getFaultInfo().getInvalidField());

        InvalidOrderFault unknownStore = assertThrows(InvalidOrderFault.class, () -> {
            SubmitOrderRequest request = validRequest("REF-B");
            request.setStoreId("ST-XX-99");
            orderService.submitOrder(request);
        });
        assertEquals(ErpFaults.CODE_ORDER_UNKNOWN_STORE, unknownStore.getFaultInfo().getErrorCode());
        assertEquals("storeId", unknownStore.getFaultInfo().getInvalidField());
        assertEquals("ST-XX-99", unknownStore.getFaultInfo().getInvalidValue());

        InvalidOrderFault unknownSku = assertThrows(InvalidOrderFault.class, () -> {
            SubmitOrderRequest request = validRequest("REF-C");
            line(request, "SKU-9999", 1);
            orderService.submitOrder(request);
        });
        assertEquals(ErpFaults.CODE_ORDER_UNKNOWN_SKU, unknownSku.getFaultInfo().getErrorCode());
        assertEquals("lines.skuId", unknownSku.getFaultInfo().getInvalidField());

        InvalidOrderFault badQuantity = assertThrows(InvalidOrderFault.class, () -> {
            SubmitOrderRequest request = validRequest("REF-D");
            line(request, "SKU-0001", 0);
            orderService.submitOrder(request);
        });
        assertEquals(ErpFaults.CODE_ORDER_BAD_QUANTITY, badQuantity.getFaultInfo().getErrorCode());
        assertEquals("0", badQuantity.getFaultInfo().getInvalidValue());

        InvalidOrderFault badRef = assertThrows(InvalidOrderFault.class, () -> {
            SubmitOrderRequest request = validRequest(" ");
            orderService.submitOrder(request);
        });
        assertEquals(ErpFaults.CODE_ORDER_BAD_REF, badRef.getFaultInfo().getErrorCode());
    }

    @Test
    void unknownOrderIdFaultsAsOrdUnknown() {
        GetOrderStatusRequest request = new GetOrderStatusRequest();
        request.setOrderId("ORD-2026-999999");

        InvalidOrderFault fault = assertThrows(InvalidOrderFault.class, () -> orderService.getOrderStatus(request));
        assertEquals(ErpFaults.CODE_ORDER_UNKNOWN, fault.getFaultInfo().getErrorCode());
        assertEquals("orderId", fault.getFaultInfo().getInvalidField());
    }

    private SubmitOrderRequest validRequest(String externalOrderRef) {
        SubmitOrderRequest request = new SubmitOrderRequest();
        request.setExternalOrderRef(externalOrderRef);
        request.setStoreId("ST-CA-01");
        request.setOrderChannel(OrderChannelType.EDI);
        LegacyAuditType audit = new LegacyAuditType();
        audit.setSourceSystem("junit");
        audit.setReceivedAt(XmlDateTimes.utcTimestamp(Instant.now()));
        audit.setCorrelationId("junit-1");
        request.setAudit(audit);
        return request;
    }

    private static void line(SubmitOrderRequest request, String skuId, int quantity) {
        OrderLineInputType line = new OrderLineInputType();
        line.setSkuId(skuId);
        line.setQuantity(quantity);
        request.getLines().add(line);
    }
}

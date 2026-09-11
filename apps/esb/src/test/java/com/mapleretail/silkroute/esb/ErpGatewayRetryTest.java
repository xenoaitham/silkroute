package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.esb.canonical.CanonicalXmlCodec;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalAudit;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalLine;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.config.EsbProperties;
import com.mapleretail.silkroute.esb.erp.ErpBusinessException;
import com.mapleretail.silkroute.esb.erp.ErpCall;
import com.mapleretail.silkroute.esb.erp.ErpCallResult;
import com.mapleretail.silkroute.esb.erp.ErpGateway;
import com.mapleretail.silkroute.esb.erp.ErpInfraException;
import com.mapleretail.silkroute.esb.erp.ErpPorts;
import com.mapleretail.silkroute.esb.erp.ErpXmlCodec;
import com.mapleretail.silkroute.esb.erp.FaultInjectionFeature;
import com.mapleretail.silkroute.esb.saga.Region;
import com.mapleretail.silkroute.esb.saga.SagaContext;
import com.mapleretail.silkroute.legacyerp.contract.common.MoneyType;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderResponse;
import com.mapleretail.silkroute.esb.xslt.XsltTransformer;

import jakarta.xml.ws.WebServiceException;
import maple.erp.orders.v1.OrderServicePortType;

/**
 * Gateway retry semantics with FAKE ERP ports (the ERP sim does not need to
 * run): retry ladder on infra failures only, exact real-attempt counting,
 * immediate business-fault propagation, and fault-injection hook behavior.
 */
class ErpGatewayRetryTest {

    private static CanonicalXmlCodec canonical;
    private static ErpXmlCodec erpXml;
    private static XsltTransformer xslt;

    @BeforeAll
    static void setUp() {
        canonical = new CanonicalXmlCodec();
        erpXml = new ErpXmlCodec();
        xslt = new XsltTransformer();
    }

    private static EsbProperties props() {
        EsbProperties properties = new EsbProperties();
        properties.getErp().getRetry().setMaxAttempts(3);
        properties.getErp().getRetry().setInitialBackoffMs(10);
        properties.getErp().getRetry().setMultiplier(2.0);
        return properties;
    }

    private static CanonicalOrder sampleOrder() {
        CanonicalOrder order = new CanonicalOrder();
        order.setExternalOrderRef("WEB-R-0001");
        order.setSourceSystem("WEB_STORE_CA");
        order.setStoreId("ST-CA-01");
        order.setChannel("WEB_STORE");
        order.setLines(List.of(new CanonicalLine("SKU-0001", 1)));
        CanonicalAudit audit = new CanonicalAudit();
        audit.setSourceSystem("WEB_STORE_CA");
        audit.setReceivedAt("2026-09-11T10:00:00.000Z");
        audit.setCorrelationId("corr-retry-1");
        order.setAudit(audit);
        return order;
    }

    @FunctionalInterface
    interface PortBehavior {
        SubmitOrderResponse apply(int call) throws Exception;
    }

    private static final class AttemptingPort implements OrderServicePortType {
        private final PortBehavior behavior;
        private int calls;

        AttemptingPort(PortBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public SubmitOrderResponse submitOrder(SubmitOrderRequest parameters)
                throws maple.erp.orders.v1.InvalidOrderFault {
            calls++;
            try {
                return behavior.apply(calls);
            } catch (maple.erp.orders.v1.InvalidOrderFault e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new RuntimeException(e);
            }
        }

        @Override
        public com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusResponse getOrderStatus(
                com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusRequest parameters)
                throws maple.erp.orders.v1.InvalidOrderFault {
            throw new UnsupportedOperationException();
        }
    }

    private static SubmitOrderResponse okResponse() {
        SubmitOrderResponse response = new SubmitOrderResponse();
        response.setOrderId("ORD-2026-000123");
        response.setExternalOrderRef("WEB-R-0001");
        response.setStatus(com.mapleretail.silkroute.legacyerp.contract.orders.OrderStatusType.SUBMITTED);
        MoneyType money = new MoneyType();
        money.setAmountMinor(12999L);
        money.setCurrency(com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType.CAD);
        response.setTotalAmount(money);
        response.setLineCount(1);
        return response;
    }

    @Test
    void retriesInfraFailureWithBackoffAndCountsRealAttempts() {
        AttemptingPort flaky = new AttemptingPort(call -> {
            if (call <= 2) {
                throw new WebServiceException(new java.net.SocketTimeoutException("read timed out"));
            }
            return okResponse();
        });
        ErpGateway gateway = new ErpGateway(new ErpPorts(flaky, null, null), xslt, canonical, erpXml,
                new FaultInjectionFeature(false), props());
        SagaContext ctx = new SagaContext(sampleOrder(), Region.CA, null);

        long started = System.nanoTime();
        ErpCallResult result = gateway.dispatch(ErpCall.submitOrder(ctx));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(!result.isBusinessError());
        assertEquals("ORD-2026-000123", result.valueAs(com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder.class)
                .getOrderId());
        assertEquals(3, ctx.getAttempts().get("order"), "2 failed + 1 successful attempt");
        assertTrue(elapsedMs >= 30, "backoff ladder 10ms+20ms must have been slept, was " + elapsedMs + "ms");
    }

    @Test
    void exhaustsRetriesOnPersistentTimeoutAndThrowsInfra() {
        AttemptingPort dead = new AttemptingPort(call -> {
            throw new WebServiceException(new java.net.ConnectException("Connection refused"));
        });
        ErpGateway gateway = new ErpGateway(new ErpPorts(dead, null, null), xslt, canonical, erpXml,
                new FaultInjectionFeature(false), props());
        SagaContext ctx = new SagaContext(sampleOrder(), Region.CA, null);

        ErpInfraException e = assertThrows(ErpInfraException.class, () -> gateway.dispatch(ErpCall.submitOrder(ctx)));
        assertTrue(e.getMessage().contains("connect failure") || e.getMessage().contains("Connection refused"));
        assertEquals(3, ctx.getAttempts().get("order"));
    }

    @Test
    void businessFaultIsImmediateOneAttemptAndReturnedAsSenderClass() {
        com.mapleretail.silkroute.legacyerp.contract.orders.InvalidOrderFault detail = new com.mapleretail.silkroute.legacyerp.contract.orders.InvalidOrderFault();
        detail.setErrorCode("ORD-DUP-REF");
        detail.setErrorMessage("duplicate externalOrderRef");
        detail.setSourceSubsystem("ORDERS");
        AttemptingPort dup = new AttemptingPort(call -> {
            throw new maple.erp.orders.v1.InvalidOrderFault("duplicate externalOrderRef", detail);
        });
        ErpGateway gateway = new ErpGateway(new ErpPorts(dup, null, null), xslt, canonical, erpXml,
                new FaultInjectionFeature(false), props());
        SagaContext ctx = new SagaContext(sampleOrder(), Region.CA, null);

        ErpCallResult result = gateway.dispatch(ErpCall.submitOrder(ctx));
        assertTrue(result.isBusinessError(), "business faults are returned, never propagated across the CB");
        assertEquals("ORD-DUP-REF", result.getBusinessError().getErrorCode());
        assertEquals(1, ctx.getAttempts().get("order"), "no retry on business faults");
    }

    @Test
    void faultInjectionDrivesDeterministicRetryExhaustionWhenEnabled() {
        AttemptingPort never = new AttemptingPort(call -> okResponse());
        ErpGateway gateway = new ErpGateway(new ErpPorts(never, null, null), xslt, canonical, erpXml,
                new FaultInjectionFeature(true), props());
        SagaContext ctx = new SagaContext(sampleOrder(), Region.CA, "fail-order");

        ErpInfraException e = assertThrows(ErpInfraException.class, () -> gateway.dispatch(ErpCall.submitOrder(ctx)));
        assertTrue(e.getMessage().contains("fault-injection"));
        assertEquals(3, ctx.getAttempts().get("order"));
        assertNotNull(e);
    }

    @Test
    void faultInjectionHeaderIsIgnoredWhenDisabled() {
        AttemptingPort fine = new AttemptingPort(call -> okResponse());
        ErpGateway gateway = new ErpGateway(new ErpPorts(fine, null, null), xslt, canonical, erpXml,
                new FaultInjectionFeature(false), props());
        SagaContext ctx = new SagaContext(sampleOrder(), Region.CA, "fail-order");

        ErpCallResult result = gateway.dispatch(ErpCall.submitOrder(ctx));
        assertTrue(!result.isBusinessError());
        assertEquals(1, ctx.getAttempts().get("order"));
    }

    @Test
    void reserveAndPricingLegsCountAttemptsPerErpCall() {
        // inventory reserve through a fake port: 1 infra failure then success
        com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveRequest[] seen = new com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveRequest[1];
        maple.erp.inventory.v1.InventoryServicePortType inventory = new maple.erp.inventory.v1.InventoryServicePortType() {
            private int calls;

            @Override
            public com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveResponse reserve(
                    com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveRequest parameters)
                    throws maple.erp.inventory.v1.OutOfStockFault {
                seen[0] = parameters;
                if (++calls == 1) {
                    throw new WebServiceException(new java.net.SocketTimeoutException("timeout"));
                }
                com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveResponse response = new com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveResponse();
                response.setReservationId("RSV-0001");
                return response;
            }

            @Override
            public com.mapleretail.silkroute.legacyerp.contract.inventory.GetStockResponse getStock(
                    com.mapleretail.silkroute.legacyerp.contract.inventory.GetStockRequest parameters) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseResponse release(
                    com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseRequest parameters)
                    throws maple.erp.inventory.v1.UnknownReservationFault {
                throw new UnsupportedOperationException();
            }
        };
        maple.erp.pricing.v1.PricingServicePortType pricing = new maple.erp.pricing.v1.PricingServicePortType() {
            @Override
            public com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuResponse priceForSku(
                    com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuRequest parameters)
                    throws maple.erp.pricing.v1.UnknownSkuFault {
                com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuResponse response = new com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuResponse();
                response.setSkuId(parameters.getSkuId());
                MoneyType money = new MoneyType();
                money.setAmountMinor(1099L);
                money.setCurrency(com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType.CAD);
                response.setUnitPrice(money);
                response.setPriceListCode("STD-2026");
                return response;
            }
        };
        ErpGateway gateway = new ErpGateway(new ErpPorts(null, inventory, pricing), xslt, canonical, erpXml,
                new FaultInjectionFeature(false), props());
        SagaContext ctx = new SagaContext(sampleOrder(), Region.CA, null);

        ErpCallResult reserved = gateway.dispatch(ErpCall.reserve(ctx, "SKU-0001", 1, 1));
        assertEquals("RSV-0001", reserved.valueAs(String.class));
        assertEquals(2, ctx.getAttempts().get("reserve"));
        assertEquals("ST-CA-01", seen[0].getStoreId());
        assertEquals("corr-retry-1-L1", seen[0].getReservationRef());

        ErpCallResult priced = gateway.dispatch(ErpCall.price(ctx, 1));
        var unitPrice = priced.valueAs(com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice.class);
        assertEquals("SKU-0001", unitPrice.getSkuId());
        assertEquals(1099L, unitPrice.getAmountMinor());
        assertEquals("CAD", unitPrice.getCurrency());
        assertEquals(1, ctx.getAttempts().get("pricing"));
        assertEquals(0, ctx.getAttempts().get("order"));
        assertEquals(0, ctx.getAttempts().get("confirm"));
    }
}

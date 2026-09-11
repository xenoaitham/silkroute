package com.mapleretail.silkroute.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapleretail.silkroute.esb.api.CanonicalOrderValidator;
import com.mapleretail.silkroute.esb.api.OrderResponseAssembler;
import com.mapleretail.silkroute.esb.api.OrderSubmissionResponse;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalAudit;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalLine;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalMoney;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;
import com.mapleretail.silkroute.esb.events.PiiMaskingPolicy;
import com.mapleretail.silkroute.esb.saga.Region;
import com.mapleretail.silkroute.esb.saga.SagaContext;

/**
 * Shared-contract success shape: keys, attempts always present, money as
 * integer minor units, route.customerRefMasked per the C1 hook, and the REST
 * body NEVER carries customerRef (only the event copy does).
 */
class ResponseAssemblyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderResponseAssembler assembler = new OrderResponseAssembler(new PiiMaskingPolicy());

    private CanonicalOrder order(String storeId, String customerRef) {
        CanonicalOrder order = new CanonicalOrder();
        order.setExternalOrderRef("WEB-1");
        order.setSourceSystem("WEB_STORE");
        order.setStoreId(storeId);
        order.setCustomerRef(customerRef);
        order.setChannel("WEB_STORE");
        order.setLines(List.of(new CanonicalLine("SKU-0001", 1)));
        CanonicalAudit audit = new CanonicalAudit();
        audit.setSourceSystem("WEB_STORE");
        audit.setReceivedAt("2026-09-11T10:00:00.000Z");
        audit.setCorrelationId("corr-shape-1");
        order.setAudit(audit);
        return order;
    }

    private SagaContext completedContext(CanonicalOrder order, Map<String, Integer> attempts) {
        SagaContext ctx = new SagaContext(order, Region.fromStoreId(order.getStoreId()), null);
        ctx.getAttempts().putAll(attempts);
        ctx.setOrderId("ORD-2026-000777");
        ctx.getReservationIds().add("RSV-1");
        ctx.getReservationIds().add("RSV-2");
        ctx.stepCompleted("order");
        ctx.stepCompleted("reserve");
        ctx.stepCompleted("pricing");
        ctx.stepCompleted("confirm");
        return ctx;
    }

    private CanonicalConfirmedOrder confirmed(String currency) {
        CanonicalConfirmedOrder confirmed = new CanonicalConfirmedOrder();
        confirmed.setOrderId("ORD-2026-000777");
        confirmed.setStatus("SUBMITTED");
        confirmed.setExternalOrderRef("WEB-1");
        confirmed.setTotalAmount(new CanonicalMoney(5698L, currency));
        return confirmed;
    }

    private List<CanonicalUnitPrice> prices() {
        return List.of(new CanonicalUnitPrice("SKU-0001", 1099L, "CAD"));
    }

    @Test
    void successBodyMatchesSharedContractShape() throws Exception {
        SagaContext ctx = completedContext(order("ST-CA-01", "cust-x"),
                Map.of("order", 1, "reserve", 1, "pricing", 2, "confirm", 1));
        OrderSubmissionResponse response = assembler.assemble(ctx, confirmed("CAD"), prices());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals("ORD-2026-000777", json.get("orderId").asText());
        assertEquals("SUBMITTED", json.get("status").asText());
        assertEquals("WEB-1", json.get("externalOrderRef").asText());
        assertEquals("ST-CA-01", json.get("storeId").asText());
        assertEquals("CA", json.get("region").asText());
        assertEquals("WEB_STORE", json.get("channel").asText());
        assertEquals("RSV-1", json.get("reservationId").asText(), "first reservation id in the single field");
        assertEquals(5698, json.get("totalAmount").get("amountMinor").asInt(), "integer minor units (C5)");
        assertEquals("CAD", json.get("totalAmount").get("currency").asText());
        assertEquals(1099, json.get("unitPrices").get(0).get("amountMinor").asInt());
        assertEquals("SKU-0001", json.get("unitPrices").get(0).get("skuId").asText());

        // saga array, in execution order, all COMPLETED
        assertEquals(4, json.get("saga").size());
        assertEquals("order", json.get("saga").get(0).get("step").asText());
        assertEquals("COMPLETED", json.get("saga").get(0).get("status").asText());
        assertEquals("confirm", json.get("saga").get(3).get("step").asText());

        // attempts ALWAYS present with all four steps and REAL counts
        JsonNode attempts = json.get("attempts");
        assertEquals(4, attempts.size());
        assertEquals(1, attempts.get("order").asInt());
        assertEquals(1, attempts.get("reserve").asInt());
        assertEquals(2, attempts.get("pricing").asInt(), "retry-recovery is visible here");
        assertEquals(1, attempts.get("confirm").asInt());

        assertEquals("CA", json.get("route").get("region").asText());
        assertFalse(json.get("route").get("customerRefMasked").asBoolean(), "CA customerRef egresses clear");
        assertEquals("WEB_STORE", json.get("audit").get("sourceSystem").asText());
        assertEquals("corr-shape-1", json.get("audit").get("correlationId").asText());

        // the REST body never carries customerRef (event copy only)
        assertTrue(!json.has("customerRef"));
    }

    @Test
    void cnOrderReportsCustomerRefMaskedTrue() throws Exception {
        SagaContext ctx = completedContext(order("ST-CN-01", "cust-zhang"), Map.of("order", 1, "reserve", 1,
                "pricing", 1, "confirm", 1));
        OrderSubmissionResponse response = assembler.assemble(ctx, confirmed("CNY"), List.of());
        assertTrue(response.getRoute().isCustomerRefMasked());
        assertTrue(assembler.egressCustomerRef(ctx).startsWith("msk-"), "event copy must carry the masked value");

        // CN order WITHOUT customerRef: nothing was masked
        SagaContext anonymous = completedContext(order("ST-CN-01", null),
                Map.of("order", 1, "reserve", 1, "pricing", 1, "confirm", 1));
        OrderSubmissionResponse anonymousResponse = assembler.assemble(anonymous, confirmed("CNY"), List.of());
        assertFalse(anonymousResponse.getRoute().isCustomerRefMasked());
    }

    // ---------------------------------------------------------- schema gate

    @Test
    void schemaRejectsUnknownFieldsWrongPatternsAndBadChannel() {
        CanonicalOrderValidator validator = new CanonicalOrderValidator();
        String good = """
                {"externalOrderRef":"WEB-1","sourceSystem":"SRC","storeId":"ST-CA-01","channel":"WEB_STORE",
                 "lines":[{"skuId":"SKU-0001","quantity":2}],
                 "audit":{"sourceSystem":"SRC","receivedAt":"2026-09-11T10:00:00Z","correlationId":"c-1"}}
                """;
        assertTrue(validator.validate(good).valid(), () -> String.join(";", validator.validate(good).errors()));

        String extraField = good.replace("\"channel\":\"WEB_STORE\",", "\"channel\":\"WEB_STORE\",\"sneaky\":1,");
        assertFalse(validator.validate(extraField).valid(), "additionalProperties:false at top level");

        String badStore = good.replace("ST-CA-01", "ST-US-99");
        assertFalse(validator.validate(badStore).valid());

        String badChannel = good.replace("WEB_STORE", "CARRIER_PIGEON");
        assertFalse(validator.validate(badChannel).valid());

        String badQuantity = good.replace("\"quantity\":2", "\"quantity\":0");
        assertFalse(validator.validate(badQuantity).valid());

        String badDate = good.replace("2026-09-11T10:00:00Z", "not-a-date");
        assertFalse(validator.validate(badDate).valid(), "audit.receivedAt must be a date-time");

        String badSku = good.replace("SKU-0001", "SKU-12345");
        assertFalse(validator.validate(badSku).valid());

        String notJson = "{oops";
        assertFalse(validator.validate(notJson).valid());
    }
}

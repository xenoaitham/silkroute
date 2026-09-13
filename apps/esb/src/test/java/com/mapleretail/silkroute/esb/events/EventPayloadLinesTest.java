package com.mapleretail.silkroute.esb.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapleretail.silkroute.esb.api.OrderSubmissionResponse;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalLine;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;
import com.mapleretail.silkroute.esb.config.EsbProperties;

/**
 * Pins the event-copy contract for the Phase-3 data plane: the Kafka success
 * event carries order LINES (skuId, quantity, unitPriceMinor, currency — C5
 * integer money) that the REST 201 body does not, mirroring the customerRef
 * injection precedent. The OMS event store rebuilds fact_order_lines from it.
 */
class EventPayloadLinesTest {

    private final ProducerTemplate producer = Mockito.mock(ProducerTemplate.class);
    private final EventPublisher publisher = new EventPublisher(
            producer, new ObjectMapper(), new PiiMaskingPolicy("sim-egress-secret"),
            new EsbProperties());

    @Test
    void eventCopyCarriesLinesWithIntegerMoney() throws Exception {
        OrderSubmissionResponse response = new OrderSubmissionResponse();
        response.setOrderId("ORD-2026-000001");
        response.setStatus("COMPLETED");
        response.setRegion("CA");

        CanonicalLine line = new CanonicalLine();
        line.setSkuId("SKU-0001");
        line.setQuantity(2);
        CanonicalUnitPrice price = new CanonicalUnitPrice();
        price.setSkuId("SKU-0001");
        price.setAmountMinor(500L);
        price.setCurrency("CAD");

        publisher.publishSuccess(response, "cust-clear", List.of(line), List.of(price));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(producer).sendBodyAndHeaders(anyString(), body.capture(), anyMap());
        JsonNode event = new ObjectMapper().readTree(body.getValue());

        JsonNode lines = event.get("lines");
        assertEquals(1, lines.size());
        assertEquals("SKU-0001", lines.get(0).get("skuId").asText());
        assertEquals(2, lines.get(0).get("quantity").asInt());
        assertEquals(500L, lines.get(0).get("unitPriceMinor").asLong());
        assertEquals("CAD", lines.get(0).get("currency").asText());
    }

    @Test
    void eventCopyMasksCnCustomerRefAndKeepsLines() throws Exception {
        OrderSubmissionResponse response = new OrderSubmissionResponse();
        response.setOrderId("ORD-2026-000002");
        response.setStatus("COMPLETED");
        response.setRegion("CN");

        CanonicalLine line = new CanonicalLine();
        line.setSkuId("SKU-0007");
        line.setQuantity(1);
        CanonicalUnitPrice price = new CanonicalUnitPrice();
        price.setSkuId("SKU-0007");
        price.setAmountMinor(12000L);
        price.setCurrency("CNY");

        publisher.publishSuccess(response, "msk-abcdef123456", List.of(line), List.of(price));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(producer).sendBodyAndHeaders(anyString(), body.capture(), anyMap());
        JsonNode event = new ObjectMapper().readTree(body.getValue());

        assertTrue(event.get("customerRef").asText().startsWith("msk-"));
        assertEquals(1, event.get("lines").size());
        assertEquals(12000L, event.get("lines").get(0).get("unitPriceMinor").asLong());
    }

    @Test
    void nullCanonicalLinesLeavesEventWithoutLinesField() throws Exception {
        OrderSubmissionResponse response = new OrderSubmissionResponse();
        response.setOrderId("ORD-2026-000003");
        response.setStatus("COMPLETED");

        publisher.publishSuccess(response, "cust-x", null, null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(producer).sendBodyAndHeaders(anyString(), body.capture(), anyMap());
        JsonNode event = new ObjectMapper().readTree(body.getValue());
        assertNull(event.get("lines"));
    }
}

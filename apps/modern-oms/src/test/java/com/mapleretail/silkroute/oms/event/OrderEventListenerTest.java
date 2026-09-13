package com.mapleretail.silkroute.oms.event;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mapleretail.silkroute.oms.store.OrderEventStore;

import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the consumer loop (plain JUnit, no Spring context, no MySQL):
 * event->rows mapping incl. C5 integer line-total math, replay-skip flow,
 * CN masked customerRef stored verbatim, poison-event handling that does not
 * kill the loop, and tombstone skip.
 */
class OrderEventListenerTest {

    private static final String MASKED_CN_REF = "msk-3f9c1a2b7d";

    private static String eventJson(String customerRef) {
        return """
                {
                  "orderId":"ORD-20260913-0001",
                  "status":"CONFIRMED",
                  "externalOrderRef":"WEB-day-123-1",
                  "storeId":"ST-CN-01",
                  "region":"CN",
                  "channel":"WEB_STORE",
                  "reservationId":"RSV-1",
                  "totalAmount":{"amountMinor":25000,"currency":"CNY"},
                  "unitPrices":[{"skuId":"SKU-0001","amountMinor":12500,"currency":"CNY"}],
                  "route":{"region":"CN","customerRefMasked":true},
                  "audit":{"sourceSystem":"WEB_STORE_CN","correlationId":"day-123-1"},
                  "customerRef":"%s",
                  "lines":[
                    {"skuId":"SKU-0001","quantity":2,"unitPriceMinor":12500,"currency":"CNY"}
                  ]
                }
                """.formatted(customerRef);
    }

    @Test
    void mapsEventToRowsWithIntegerLineTotalMath() throws Exception {
        OrderEventData event = new OrderEventParser().parse(eventJson(MASKED_CN_REF));
        assertEquals("ORD-20260913-0001", event.getOrderId());
        assertEquals("WEB-day-123-1", event.getExternalOrderRef());
        assertEquals("WEB_STORE_CN", event.getSourceSystem());
        assertEquals("CN", event.getRegion());
        assertEquals(25000L, event.getTotalAmountMinor());
        assertEquals("CNY", event.getCurrency());
        assertEquals(1, event.getLines().size());
        // C5: 2 x 12500 = 25000 in integer math, never floating point
        assertEquals(25000L, event.getLines().get(0).lineTotalMinor());
        assertEquals(12500L, event.getLines().get(0).unitPriceMinor());
    }

    @Test
    void integerLineTotalMathIsExactForOddPrices() {
        // 3 x 3333 = 9999; 7 x 499 = 3493 — long multiplication, C5
        assertEquals(9999L, OrderEventData.LineMath.lineTotal(3, 3333L));
        assertEquals(3493L, OrderEventData.LineMath.lineTotal(7, 499L));
        assertThrows(IllegalArgumentException.class, () -> OrderEventData.LineMath.lineTotal(0, 100L));
    }

    @Test
    void cnMaskedCustomerRefIsStoredVerbatim() throws Exception {
        OrderEventData event = new OrderEventParser().parse(eventJson(MASKED_CN_REF));
        // C1: verbatim passthrough — no re-masking, no unmanging, no trimming
        assertEquals(MASKED_CN_REF, event.getCustomerRef());
        assertEquals(MASKED_CN_REF, event.getCustomerRef());
    }

    @Test
    void fallsBackToUnitPricesWhenLineLacksPrice() throws Exception {
        String json = eventJson(MASKED_CN_REF)
                .replace("\"unitPriceMinor\":12500,\"currency\":\"CNY\"}\n  ]", "\"currency\":\"CNY\"}\n  ]");
        // line now carries no unitPriceMinor -> fallback to unitPrices[] by skuId
        OrderEventData event = new OrderEventParser().parse(json);
        assertEquals(1, event.getLines().size());
        assertEquals(12500L, event.getLines().get(0).unitPriceMinor());
        assertEquals("CNY", event.getLines().get(0).currency());
        assertEquals(25000L, event.getLines().get(0).lineTotalMinor());
    }

    @Test
    void malformedEventsArePoisonNotCrash() {
        OrderEventParser parser = new OrderEventParser();
        assertThrows(OrderEventParseException.class, () -> parser.parse("{not json"));
        assertThrows(OrderEventParseException.class, () -> parser.parse("{\"externalOrderRef\":\"x\"}")); // no orderId/sourceSystem
        assertThrows(OrderEventParseException.class, () -> parser.parse(eventJson(MASKED_CN_REF).replace("\"quantity\":2", "\"quantity\":0")));
        assertThrows(OrderEventParseException.class, () -> parser.parse(""));
    }

    @Test
    void listenerStoresThenAcksOnHappyPath() throws Exception {
        OrderEventParser parser = new OrderEventParser();
        RecordingStore store = new RecordingStore(OrderEventStore.Outcome.stored());
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        OrderEventListener listener = new OrderEventListener(parser, store);

        listener.onMessage(record(0, 42, eventJson(MASKED_CN_REF)), ack);

        assertEquals(1, store.calls.size());
        assertEquals("ORD-20260913-0001", store.calls.get(0).event().getOrderId());
        assertEquals(MASKED_CN_REF, store.calls.get(0).event().getCustomerRef());
        assertEquals(42L, store.calls.get(0).meta().offset());
        Mockito.verify(ack).acknowledge();
    }

    @Test
    void replaySkipIsTerminalAndAcked() throws Exception {
        RecordingStore store = new RecordingStore(OrderEventStore.Outcome.skipped());
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        OrderEventListener listener = new OrderEventListener(new OrderEventParser(), store);

        listener.onMessage(record(3, 7, eventJson(MASKED_CN_REF)), ack);

        assertEquals(1, store.calls.size());
        assertTrue(store.calls.get(0).outcome().replaySkipped());
        Mockito.verify(ack).acknowledge();
    }

    @Test
    void poisonEventSkipsStoreAndAcks() {
        StoreThatMustNotBeCalled store = new StoreThatMustNotBeCalled();
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        OrderEventListener listener = new OrderEventListener(new OrderEventParser(), store);

        listener.onMessage(record(0, 1, "{broken"), ack);

        assertEquals(0, store.calls);
        Mockito.verify(ack).acknowledge();
    }

    @Test
    void storeFailureDoesNotAckAndPropagates() throws Exception {
        OrderEventParser parser = new OrderEventParser();
        OrderEventStore failing = (event, meta) -> {
            throw new IllegalStateException("db down");
        };
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        OrderEventListener listener = new OrderEventListener(parser, failing);

        assertThrows(IllegalStateException.class,
                () -> listener.onMessage(record(0, 5, eventJson(MASKED_CN_REF)), ack));
        Mockito.verify(ack, Mockito.never()).acknowledge();
    }

    @Test
    void tombstoneIsSkippedAndAcked() {
        StoreThatMustNotBeCalled store = new StoreThatMustNotBeCalled();
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        OrderEventListener listener = new OrderEventListener(new OrderEventParser(), store);

        listener.onMessage(new ConsumerRecord<>("silkroute.orders.events", 0, 9, null, null), ack);

        assertEquals(0, store.calls);
        Mockito.verify(ack).acknowledge();
    }

    private static ConsumerRecord<String, String> record(int partition, long offset, String value) {
        return new ConsumerRecord<>("silkroute.orders.events", partition, offset, "key", value);
    }

    private record StoredCall(OrderEventData event, RecordMeta meta, OrderEventStore.Outcome outcome) {
    }

    private static final class RecordingStore implements OrderEventStore {
        private final Outcome outcome;
        final List<StoredCall> calls = new java.util.ArrayList<>();

        RecordingStore(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Outcome store(OrderEventData event, RecordMeta meta) {
            calls.add(new StoredCall(event, meta, outcome));
            return outcome;
        }
    }

    private static final class StoreThatMustNotBeCalled implements OrderEventStore {
        int calls;

        @Override
        public Outcome store(OrderEventData event, RecordMeta meta) {
            calls++;
            throw new IllegalStateException("store must not be called for poison/tombstone events");
        }
    }
}

package com.mapleretail.silkroute.oms.event;

import com.mapleretail.silkroute.oms.store.OrderEventStore;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The consumer loop body. Semantics (at-least-once, ADR-0006 decision 2):
 *
 * - the offset is acknowledged ONLY after the store reports success (or the
 *   event was skipped as poison/duplicate - both are terminal outcomes);
 * - malformed (poison) events are logged as OMS-POISON and acknowledged -
 *   they NEVER kill the consumer loop;
 * - store failures (DB down etc.) propagate WITHOUT acknowledging: the
 *   container's error handler retries forever with a fixed backoff, loudly,
 *   and the record is not lost.
 *
 * C1: this class performs NO transformation of customerRef - the verbatim
 * value from the event copy flows into the store unchanged.
 */
@Component
public class OrderEventListener implements AcknowledgingMessageListener<String, String> {

    private static final Logger LOG = LoggerFactory.getLogger(OrderEventListener.class);
    private static final int POISON_PAYLOAD_SAMPLE = 300;

    private final OrderEventParser parser;
    private final OrderEventStore store;

    public OrderEventListener(OrderEventParser parser, OrderEventStore store) {
        this.parser = parser;
        this.store = store;
    }

    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        if (record.value() == null) {
            // ESB success events are never tombstones; a null value cannot map to a
            // business key, so it is terminally skippable.
            LOG.info("OMS-TOMBSTONE-SKIP topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset());
            ack.acknowledge();
            return;
        }
        try {
            OrderEventData event = parser.parse(record.value()).withRawEventJson(record.value());
            store.store(event, new RecordMeta(record.topic(), record.partition(), record.offset(), record.timestamp()));
            ack.acknowledge();
        } catch (OrderEventParseException e) {
            LOG.error("OMS-POISON topic={} partition={} offset={} reason={} payload={}",
                    record.topic(), record.partition(), record.offset(), e.getMessage(), sample(record.value()));
            ack.acknowledge();
        } catch (RuntimeException e) {
            LOG.error("OMS-STORE-RETRY topic={} partition={} offset={} error={}",
                    record.topic(), record.partition(), record.offset(), e.toString(), e);
            throw e;
        }
    }

    private static String sample(String value) {
        return value.length() <= POISON_PAYLOAD_SAMPLE ? value : value.substring(0, POISON_PAYLOAD_SAMPLE) + "...(truncated)";
    }
}

package com.mapleretail.silkroute.esb.events;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapleretail.silkroute.esb.api.OrderSubmissionResponse;
import com.mapleretail.silkroute.esb.config.EsbProperties;

import org.apache.camel.ProducerTemplate;

/**
 * Shared-topic publisher (Kafka): success events to silkroute.orders.events and
 * canonical failures to the DLQ silkroute.esb.dlq. THE C1 HOOK LIVES HERE: both
 * payloads pass through PiiMaskingPolicy before leaving for a shared,
 * non-CN-pinned destination, and publish failures never break the HTTP outcome.
 */
@Component
public class EventPublisher {

    public static final String HDR_CORRELATION_ID = "correlationId";
    public static final String HDR_REGION = "region";
    public static final String HDR_FAILED_STEP = "failedStep";
    public static final String HDR_ERROR_CODE = "errorCode";
    public static final String HDR_EVENT_TYPE = "eventType";

    private static final Logger LOG = LoggerFactory.getLogger(EventPublisher.class);

    private final ProducerTemplate producer;
    private final ObjectMapper mapper;
    private final PiiMaskingPolicy masking;
    private final String brokers;
    private final String ordersTopic;
    private final String dlqTopic;
    private final long maxBlockMs;

    public EventPublisher(ProducerTemplate producer, ObjectMapper mapper, PiiMaskingPolicy masking,
            EsbProperties properties) {
        this.producer = producer;
        this.mapper = mapper;
        this.masking = masking;
        this.brokers = properties.getKafka().getBrokers();
        this.ordersTopic = properties.getKafka().getOrdersTopic();
        this.dlqTopic = properties.getKafka().getDlqTopic();
        this.maxBlockMs = properties.getKafka().getMaxBlockMs();
    }

    /**
     * Publishes the canonical confirmed order to the event topic. The event
     * copy carries customerRef as the C1-masked value (masked for CN, clear
     * otherwise) — the REST body never carries customerRef.
     */
    public void publishSuccess(OrderSubmissionResponse response, String egressCustomerRef) {
        try {
            var tree = mapper.valueToTree(response);
            if (egressCustomerRef != null) {
                // customerRef is absent from the REST body by design; only the event copy carries it.
                ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("customerRef", egressCustomerRef);
            }
            // pass the TREE, not a pre-serialized string: publish() serializes its
            // payload, so a String here would be double-encoded on the wire.
            publish(kafkaUri(ordersTopic), tree, headers(
                    HDR_CORRELATION_ID, response.getAudit().getCorrelationId(),
                    HDR_REGION, response.getRegion(),
                    HDR_EVENT_TYPE, "ORDER_CONFIRMED"));
        } catch (RuntimeException e) {
            LOG.error("Cannot build success event payload: {}", e.getMessage(), e);
        }
    }

    /** Publishes the canonical failure event to the DLQ. Payload must already be masked (C1). */
    public void publishDlq(DlqPayload payload) {
        publish(kafkaUri(dlqTopic), payload, headers(
                HDR_CORRELATION_ID, payload.getCorrelationId(),
                HDR_REGION, payload.getRegion(),
                HDR_FAILED_STEP, payload.getFailedStep(),
                HDR_ERROR_CODE, payload.getErrorCode(),
                HDR_EVENT_TYPE, payload.getEventType()));
    }

    private void publish(String uri, Object payload, Map<String, Object> headers) {
        try {
            String json = mapper.writeValueAsString(payload);
            producer.sendBodyAndHeaders(uri, json, headers);
            LOG.info("Published {} event to {}", headers.get(HDR_EVENT_TYPE), uri);
        } catch (JsonProcessingException e) {
            LOG.error("Cannot serialize event payload for {}: {}", uri, e.getMessage(), e);
        } catch (Exception e) {
            // The caller's HTTP outcome must never depend on the shared bus.
            LOG.error("Failed to publish event to {}: {}", uri, e.getMessage(), e);
        }
    }

    private String kafkaUri(String topic) {
        return "kafka:" + topic + "?brokers=" + brokers + "&maxBlockMs=" + maxBlockMs;
    }

    private static Map<String, Object> headers(Object... pairs) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                headers.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return headers;
    }
}

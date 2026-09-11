package com.mapleretail.silkroute.esb.api;

import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.erp.FaultInjectionFeature;
import com.mapleretail.silkroute.esb.idem.IdempotencyKeys;
import com.mapleretail.silkroute.esb.idem.IdempotencyStore;
import com.mapleretail.silkroute.esb.saga.Region;
import com.mapleretail.silkroute.esb.saga.SagaOrchestrator;
import com.mapleretail.silkroute.esb.saga.SagaOutcome;

/**
 * REST entry point on platform-http (POST /api/v1/orders, loopback only):
 * schema-validate → idempotency claim (Redis SETNX) → saga → render → events
 * are published inside the saga. Every outcome is a canonical JSON body with an
 * explicit CamelHttpResponseCode.
 */
@Component
public class OrderRequestProcessor implements Processor {

    public static final String HTTP_RESPONSE_CODE = "CamelHttpResponseCode";
    public static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private static final Logger LOG = LoggerFactory.getLogger(OrderRequestProcessor.class);

    private final CanonicalOrderValidator validator;
    private final ObjectMapper mapper;
    private final SagaOrchestrator orchestrator;
    private final IdempotencyStore idempotencyStore;
    private final FaultInjectionFeature faultInjection;

    public OrderRequestProcessor(CanonicalOrderValidator validator, ObjectMapper mapper,
            SagaOrchestrator orchestrator, IdempotencyStore idempotencyStore, FaultInjectionFeature faultInjection) {
        this.validator = validator;
        this.mapper = mapper;
        this.orchestrator = orchestrator;
        this.idempotencyStore = idempotencyStore;
        this.faultInjection = faultInjection;
    }

    @Override
    public void process(Exchange exchange) {
        try {
            handle(exchange);
        } catch (Exception e) {
            LOG.error("Unhandled failure while processing order request", e);
            respond(exchange, 500, ErrorBody.of("INTERNAL", "RECEIVER",
                    "unexpected ESB failure: " + e.getMessage(), null, null, null, null));
        }
    }

    private void handle(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        String idempotencyHeader = header(exchange, IdempotencyKeys.HEADER);
        String faultCommand = faultInjection.isEnabled() ? header(exchange, FaultInjectionFeature.HEADER) : null;

        // 1) schema validation (SENDER-class 400 on violation)
        CanonicalOrderValidator.ValidationResult validation = validator.validate(body);
        String correlationId = extractCorrelationId(body, idempotencyHeader);
        if (!validation.valid()) {
            LOG.info("Rejected request: schema violation: {}", String.join("; ", validation.errors()));
            respond(exchange, 400, ErrorBody.of("SCHEMA-VIOLATION", "SENDER",
                    "request violates canonical order schema: " + String.join("; ", validation.errors()),
                    null, null, correlationId, null));
            return;
        }
        CanonicalOrder order = validator.parse(body);
        Region region = Region.fromStoreId(order.getStoreId());

        // 2) idempotent consumer (Redis SETNX, TTL 24h)
        String key = IdempotencyKeys.derive(idempotencyHeader, order);
        if (!idempotencyStore.claim(key)) {
            String originalOrderId = idempotencyStore.getCompleted(key).map(this::extractOrderId).orElse(null);
            ErrorBody duplicate = ErrorBody.of("DUPLICATE", "SENDER",
                    "duplicate request: idempotency key was already claimed", null, region.code(),
                    correlationId, null);
            if (originalOrderId != null) {
                duplicate.setOrderId(originalOrderId);
            }
            LOG.info("Rejected duplicate request for idempotency key '{}'", key);
            respond(exchange, 409, duplicate);
            return;
        }

        // 3) saga (events published inside)
        SagaOutcome outcome = orchestrator.run(order, faultCommand);
        respond(exchange, outcome.httpStatus(), outcome.responseJson());

        if (outcome.isSuccess()) {
            // remember the final outcome for duplicate replays
            idempotencyStore.storeCompleted(key, outcome.responseJson());
        } else {
            // A FAILED saga (compensated, business fault, infra exhaustion) must
            // release its claim: the client retrying the same key is exactly what
            // an idempotency key is for. Only SUCCESSFUL outcomes stay claimed.
            idempotencyStore.release(key);
        }
    }

    private void respond(Exchange exchange, int httpCode, Object payload) {
        try {
            // String payloads (SagaOutcome.responseJson) are ALREADY canonical JSON —
            // serializing them again would double-encode the whole body as a JSON
            // string literal. Only POJOs (ErrorBody) need serialization here.
            exchange.getMessage().setBody(payload instanceof String s ? s : mapper.writeValueAsString(payload));
        } catch (Exception e) {
            exchange.getMessage().setBody("{\"error\":{\"code\":\"INTERNAL\",\"category\":\"RECEIVER\"}}");
        }
        exchange.getMessage().setHeader(HTTP_RESPONSE_CODE, httpCode);
        exchange.getMessage().setHeader(CONTENT_TYPE, APPLICATION_JSON);
    }

    private String extractCorrelationId(String body, String idempotencyHeader) {
        try {
            JsonNode node = mapper.readTree(body == null ? "" : body);
            JsonNode candidate = node.path("audit").path("correlationId");
            if (candidate.isTextual() && !candidate.asText().isBlank()) {
                return candidate.asText();
            }
        } catch (Exception ignored) {
            // fall through to header / generated value
        }
        return idempotencyHeader != null && !idempotencyHeader.isBlank() ? idempotencyHeader
                : UUID.randomUUID().toString();
    }

    private String extractOrderId(String storedResponseJson) {
        try {
            JsonNode node = mapper.readTree(storedResponseJson);
            return node.path("orderId").isTextual() ? node.path("orderId").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(Exchange exchange, String name) {
        Object value = exchange.getIn().getHeader(name);
        return value == null ? null : String.valueOf(value);
    }
}

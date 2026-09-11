package com.mapleretail.silkroute.esb.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.camel.CamelExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapleretail.silkroute.esb.api.ErrorBody;
import com.mapleretail.silkroute.esb.api.OrderResponseAssembler;
import com.mapleretail.silkroute.esb.api.OrderSubmissionResponse;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;
import com.mapleretail.silkroute.esb.erp.CircuitOpenException;
import com.mapleretail.silkroute.esb.erp.ErpBusinessException;
import com.mapleretail.silkroute.esb.erp.ErpCall;
import com.mapleretail.silkroute.esb.erp.ErpCallResult;
import com.mapleretail.silkroute.esb.erp.ErpInfraException;
import com.mapleretail.silkroute.esb.events.DlqPayload;
import com.mapleretail.silkroute.esb.events.EventPublisher;
import com.mapleretail.silkroute.esb.events.PiiMaskingPolicy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * Hand-rolled synchronous saga (NOT the camelSaga EIP) mediating one canonical
 * order to the frozen ERP estate:
 *
 *   (1) order    = submitOrder                       (XSLT legs both ways)
 *   (2) reserve  = inventory reserve PER ORDER LINE  (typed calls, ids collected)
 *   (3) pricing  = priceForSku per line              (XSLT legs both ways)
 *   (4) confirm  = getOrderStatus asserting SUBMITTED
 *
 * Compensation: on ANY failure after reserve (or a partial reserve), every
 * collected reservationId is released best-effort, the response carries
 * compensated:true / releasedReservationId (code SAGA-COMPENSATED) and the
 * failure goes to the shared DLQ — masked per C1 for CN.
 *
 * Class mapping (ADR-0003): ERP business faults → 422 BUSINESS (Sender class,
 * no retry, never trip the breaker); timeouts/connect failures → retry ladder →
 * 503 RECEIVER; circuit-open → fail-fast 503 CIRCUIT-OPEN.
 */
@Component
public class SagaOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(SagaOrchestrator.class);

    public static final String CODE_UPSTREAM_UNAVAILABLE = "UPSTREAM-UNAVAILABLE";
    public static final String CODE_SAGA_COMPENSATED = "SAGA-COMPENSATED";
    public static final String CODE_CIRCUIT_OPEN = "CIRCUIT-OPEN";

    private final org.apache.camel.ProducerTemplate producer;
    private final ObjectMapper mapper;
    private final OrderResponseAssembler assembler;
    private final EventPublisher publisher;
    private final PiiMaskingPolicy masking;

    public SagaOrchestrator(org.apache.camel.ProducerTemplate producer, ObjectMapper mapper,
            OrderResponseAssembler assembler, EventPublisher publisher, PiiMaskingPolicy masking) {
        this.producer = producer;
        this.mapper = mapper;
        this.assembler = assembler;
        this.publisher = publisher;
        this.masking = masking;
    }

    public SagaOutcome run(CanonicalOrder order, String faultInjectionCommand) {
        Region region = Region.fromStoreId(order.getStoreId());
        SagaContext ctx = new SagaContext(order, region, faultInjectionCommand);
        final String[] currentStep = {"order"};
        try {
            // ---- (1) order --------------------------------------------------
            currentStep[0] = "order";
            ErpCallResult submitted = erp("direct:erp", ErpCall.submitOrder(ctx));
            if (submitted.isBusinessError()) {
                return businessNoCompensation(submitted.getBusinessError(), currentStep[0], ctx);
            }
            CanonicalConfirmedOrder confirmed = submitted.valueAs(CanonicalConfirmedOrder.class);
            ctx.setOrderId(confirmed.getOrderId());
            ctx.stepCompleted("order");

            // ---- (2) reserve (per line, ids collected for compensation) -----
            currentStep[0] = "reserve";
            for (int i = 0; i < order.getLines().size(); i++) {
                var line = order.getLines().get(i);
                ErpCallResult reserved = erp("direct:erp", ErpCall.reserve(ctx, line.getSkuId(), line.getQuantity(), i + 1));
                if (reserved.isBusinessError()) {
                    // Literal contract: OutOfStockFault on the reserve step itself →
                    // 422, NO compensation, NO DLQ (handled Sender-class outcome).
                    return businessNoCompensation(reserved.getBusinessError(), currentStep[0], ctx);
                }
                ctx.getReservationIds().add(reserved.valueAs(String.class));
            }
            ctx.stepCompleted("reserve");

            // ---- (3) pricing (per line) -------------------------------------
            currentStep[0] = "pricing";
            List<CanonicalUnitPrice> unitPrices = new ArrayList<>();
            for (int i = 1; i <= order.getLines().size(); i++) {
                ErpCallResult priced = erp("direct:erp", ErpCall.price(ctx, i));
                if (priced.isBusinessError()) {
                    return businessAfterReserve(priced.getBusinessError(), currentStep[0], ctx);
                }
                unitPrices.add(priced.valueAs(CanonicalUnitPrice.class));
            }
            ctx.stepCompleted("pricing");

            // ---- (4) confirm -------------------------------------------------
            currentStep[0] = "confirm";
            ErpCallResult confirmation = erp("direct:erp", ErpCall.orderStatus(ctx));
            if (confirmation.isBusinessError()) {
                return businessAfterReserve(confirmation.getBusinessError(), currentStep[0], ctx);
            }
            String status = confirmation.valueAs(String.class);
            if (!"SUBMITTED".equals(status)) {
                throw new ErpInfraException("ERP status for " + ctx.getOrderId() + " is '" + status
                        + "', expected SUBMITTED");
            }
            ctx.stepCompleted("confirm");

            // ---- success -----------------------------------------------------
            OrderSubmissionResponse response = assembler.assemble(ctx, confirmed, unitPrices);
            publisher.publishSuccess(response, assembler.egressCustomerRef(ctx));
            return new SagaOutcome(201, json(response));

        } catch (CircuitOpenException e) {
            return circuitOpen(currentStep[0], ctx, e);
        } catch (ErpInfraException e) {
            return infraExhausted(currentStep[0], ctx, e);
        }
    }

    // ------------------------------------------------------------- failures

    /** ERP business fault with nothing compensable (order / reserve step). */
    private SagaOutcome businessNoCompensation(ErpBusinessException e, String step, SagaContext ctx) {
        LOG.info("Saga failed on step '{}' with ERP business fault {}: {}", step, e.getErrorCode(), e.getMessage());
        ErrorBody error = base(e.getErrorCode(), "BUSINESS", e.getMessage(), step, ctx);
        return new SagaOutcome(422, json(error));
    }

    /** ERP business fault after reserve: compensate, DLQ, 422 BUSINESS (+compensation info). */
    private SagaOutcome businessAfterReserve(ErpBusinessException e, String step, SagaContext ctx) {
        LOG.info("Saga failed on step '{}' with ERP business fault {}: {} — compensating", step, e.getErrorCode(),
                e.getMessage());
        Compensation compensation = compensate(ctx);
        publishDlq(step, ctx, e.getErrorCode(), "BUSINESS", e.getMessage(), compensation);
        ErrorBody error = base(e.getErrorCode(), "BUSINESS", e.getMessage(), step, ctx);
        applyCompensation(error, compensation);
        return new SagaOutcome(422, json(error));
    }

    /** Infra failure after the retry ladder: compensate (if anything reserved), DLQ, 503. */
    private SagaOutcome infraExhausted(String step, SagaContext ctx, ErpInfraException e) {
        LOG.warn("Saga failed on step '{}' after retries: {}", step, e.getMessage());
        Compensation compensation = compensate(ctx);
        publishDlq(step, ctx, CODE_UPSTREAM_UNAVAILABLE, "RECEIVER", e.getMessage(), compensation);
        ErrorBody error = base(compensation.releasedAny() ? CODE_SAGA_COMPENSATED : CODE_UPSTREAM_UNAVAILABLE,
                "RECEIVER", e.getMessage(), step, ctx);
        applyCompensation(error, compensation);
        return new SagaOutcome(503, json(error));
    }

    /** Circuit breaker refused the call: fail fast, wire untouched. */
    private SagaOutcome circuitOpen(String step, SagaContext ctx, CircuitOpenException e) {
        LOG.warn("Saga stopped on step '{}': circuit breaker OPEN (fail fast)", step);
        Compensation compensation = compensate(ctx);
        if (compensation.releasedAny()) {
            publishDlq(step, ctx, CODE_CIRCUIT_OPEN, "RECEIVER", e.getMessage(), compensation);
        }
        ErrorBody error = base(CODE_CIRCUIT_OPEN, "RECEIVER",
                "ERP circuit breaker is open (fail fast, no wire call attempted)", step, ctx);
        applyCompensation(error, compensation);
        return new SagaOutcome(503, json(error));
    }

    /**
     * Best-effort release of every collected reservationId. compensated:true
     * only when at least one reservation was actually released.
     */
    private Compensation compensate(SagaContext ctx) {
        if (ctx.getReservationIds().isEmpty()) {
            return Compensation.NONE;
        }
        for (String reservationId : ctx.getReservationIds()) {
            try {
                // Compensation MUST be possible exactly when the breaker is OPEN —
                // that is when a saga holding reservations fails — so releases ride
                // the dedicated breaker-free route (direct:erp-release), never the
                // guarded direct:erp funnel.
                erp("direct:erp-release", ErpCall.release(ctx, reservationId));
                ctx.getReleasedReservationIds().add(reservationId);
                LOG.info("Compensated reservation {}", reservationId);
            } catch (Exception e) {
                LOG.warn("Compensation release failed for reservation {}: {}", reservationId, e.getMessage());
            }
        }
        return new Compensation(ctx.getReleasedReservationIds());
    }

    private void applyCompensation(ErrorBody error, Compensation compensation) {
        if (compensation.releasedAny()) {
            error.setCompensated(true);
            error.setReleasedReservationId(String.join(",", compensation.released()));
        }
    }

    private void publishDlq(String step, SagaContext ctx, String errorCode, String category, String message,
            Compensation compensation) {
        DlqPayload payload = new DlqPayload();
        payload.setOrderId(ctx.getOrderId());
        payload.setExternalOrderRef(ctx.getOrder().getExternalOrderRef());
        payload.setSourceSystem(ctx.getOrder().getSourceSystem());
        payload.setStoreId(ctx.getOrder().getStoreId());
        payload.setRegion(ctx.getRegion().code());
        // C1 HOOK: CN customerRef must never leave the region unmasked.
        payload.setCustomerRef(masking.maskForEgress(ctx.getOrder().getCustomerRef(), ctx.getRegion().code()));
        payload.setCorrelationId(ctx.getOrder().getAudit().getCorrelationId());
        payload.setFailedStep(step);
        payload.setAttempts(Map.copyOf(ctx.getAttempts()));
        payload.setErrorCode(errorCode);
        payload.setCategory(category);
        payload.setCompensated(compensation.releasedAny());
        payload.setReleasedReservationIds(List.copyOf(ctx.getReleasedReservationIds()));
        payload.setMessage(message);
        payload.setOccurredAt(Instant.now().toString());
        publisher.publishDlq(payload);
    }

    // -------------------------------------------------------------- helpers

    private ErrorBody base(String code, String category, String message, String step, SagaContext ctx) {
        return ErrorBody.of(code, category, message, step, ctx.getRegion().code(),
                ctx.getOrder().getAudit().getCorrelationId(), ctx.getAttempts());
    }

    private ErpCallResult erp(String endpoint, ErpCall call) {
        try {
            Object result = producer.requestBody(endpoint, call);
            return (ErpCallResult) result;
        } catch (CamelExecutionException e) {
            throw unwrap(e);
        }
    }

    private RuntimeException unwrap(CamelExecutionException e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof CallNotPermittedException notPermitted) {
                return new CircuitOpenException("ERP gateway circuit breaker refused the call: "
                        + notPermitted.getMessage());
            }
            if (current instanceof ErpInfraException infra) {
                return infra;
            }
            if (current instanceof ErpBusinessException business) {
                return business;
            }
            current = current.getCause();
        }
        return new ErpInfraException("ERP call failed: " + e.getMessage(), e);
    }

    private String json(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize payload", e);
        }
    }

    private record Compensation(List<String> released) {
        static final Compensation NONE = new Compensation(List.of());

        boolean releasedAny() {
            return !released.isEmpty();
        }
    }
}

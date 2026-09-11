package com.mapleretail.silkroute.esb.erp;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.esb.canonical.CanonicalXmlCodec;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;
import com.mapleretail.silkroute.esb.config.EsbProperties;
import com.mapleretail.silkroute.esb.saga.SagaContext;
import com.mapleretail.silkroute.legacyerp.contract.common.LegacyAuditType;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveRequest;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveResponse;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseRequest;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseResponse;
import com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusResponse;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderResponse;
import com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuRequest;
import com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuResponse;
import com.mapleretail.silkroute.esb.xslt.XsltTransformer;

/**
 * The mediated ERP gateway: the ONLY place that talks to the frozen SOAP estate
 * (constraint C6). Every call goes through here on the circuit-breaker route
 * (direct:erp, camel-resilience4j).
 *
 * Mediation performed:
 *  - orders leg: canonical XML --canonical-to-submit-request.xsl--> submitOrderRequest
 *                submitOrderResponse --submit-response-to-canonical.xsl--> canonical confirmed order
 *  - pricing leg: canonical XML --canonical-to-price-request.xsl--> priceForSkuRequest
 *                priceForSkuResponse --price-response-to-canonical.xsl--> canonical unit price
 *  - inventory reserve/release: direct typed CXF calls (frozen contract, no XSLT required)
 *
 * Resilience performed here: retry with exponential backoff on INFRA errors only
 * (timeout/connect — never on ERP business faults, ADR-0003), counting real
 * attempts into the saga's attempts object. Business faults are returned as
 * {@link ErpCallResult#business} so they do NOT trip the circuit breaker; infra
 * failures propagate as {@link ErpInfraException} and DO.
 */
@Component
public class ErpGateway {

    public static final String XSL_CANONICAL_TO_SUBMIT = "xslt/canonical-to-submit-request.xsl";
    public static final String XSL_SUBMIT_TO_CANONICAL = "xslt/submit-response-to-canonical.xsl";
    public static final String XSL_CANONICAL_TO_PRICE = "xslt/canonical-to-price-request.xsl";
    public static final String XSL_PRICE_TO_CANONICAL = "xslt/price-response-to-canonical.xsl";

    private static final Logger LOG = LoggerFactory.getLogger(ErpGateway.class);

    /** Port invocation with the checked fault exceptions of the frozen contracts. */
    @FunctionalInterface
    interface PortCall<T> {
        T call() throws Exception;
    }

    private final ErpPorts ports;
    private final XsltTransformer xslt;
    private final CanonicalXmlCodec canonical;
    private final ErpXmlCodec erpXml;
    private final FaultInjectionFeature faultInjection;
    private final EsbProperties.Erp.Retry retry;

    public ErpGateway(ErpPorts ports, XsltTransformer xslt, CanonicalXmlCodec canonical, ErpXmlCodec erpXml,
            FaultInjectionFeature faultInjection, EsbProperties properties) {
        this.ports = ports;
        this.xslt = xslt;
        this.canonical = canonical;
        this.erpXml = erpXml;
        this.faultInjection = faultInjection;
        this.retry = properties.getErp().getRetry();
    }

    /** Camel bean entry point on the circuit-breaker route (body = ErpCall). */
    public ErpCallResult dispatch(ErpCall call) {
        try {
            return switch (call.getOp()) {
                case SUBMIT_ORDER -> ErpCallResult.success(submitOrder(call.getCtx()));
                case RESERVE -> ErpCallResult.success(reserve(call));
                case RELEASE -> ErpCallResult.success(release(call));
                case PRICE -> ErpCallResult.success(priceForSku(call));
                case GET_ORDER_STATUS -> ErpCallResult.success(orderStatus(call.getCtx()));
            };
        } catch (ErpBusinessException e) {
            // Sender class (ADR-0003): returned, never recorded by the breaker.
            return ErpCallResult.business(e);
        }
        // ErpInfraException propagates: the circuit breaker records it.
    }

    // ------------------------------------------------------------------ legs

    CanonicalConfirmedOrder submitOrder(SagaContext ctx) {
        String canonicalXml = canonical.marshal(ctx.getOrder());
        String requestXml = xslt.transform(XSL_CANONICAL_TO_SUBMIT, canonicalXml);
        SubmitOrderRequest request = erpXml.parseSubmitRequest(requestXml);
        SubmitOrderResponse response = withRetry("order", ctx, () -> ports.orders().submitOrder(request));
        String confirmedXml = xslt.transform(XSL_SUBMIT_TO_CANONICAL, erpXml.toXml(response),
                Map.of("storeId", nz(ctx.getOrder().getStoreId()), "channel", nz(ctx.getOrder().getChannel())));
        return canonical.unmarshalConfirmedOrder(confirmedXml);
    }

    String reserve(ErpCall call) {
        SagaContext ctx = call.getCtx();
        LegacyAuditType audit = auditOf(ctx);
        ReserveRequest request = erpXml.reserveRequest(
                ctx.getOrder().getAudit().getCorrelationId() + "-L" + call.getLineIndex(),
                ctx.getOrder().getStoreId(), call.getSkuId(), call.getQuantity(), audit);
        ReserveResponse response = withRetry("reserve", ctx,
                () -> ports.inventory().reserve(request));
        return response.getReservationId();
    }

    Boolean release(ErpCall call) {
        SagaContext ctx = call.getCtx();
        ReleaseRequest request = erpXml.releaseRequest(call.getReservationId(), auditOf(ctx));
        // Best-effort compensation: single attempt, no ladder, failures logged by the orchestrator.
        ReleaseResponse response = once(() -> ports.inventory().release(request));
        return response.isFullyReleased();
    }

    CanonicalUnitPrice priceForSku(ErpCall call) {
        SagaContext ctx = call.getCtx();
        String requestXml = xslt.transform(XSL_CANONICAL_TO_PRICE, canonical.marshal(ctx.getOrder()),
                Map.of("currency", ctx.getCurrency(), "lineIndex", call.getLineIndex()));
        PriceForSkuRequest request = erpXml.parsePriceRequest(requestXml);
        PriceForSkuResponse response = withRetry("pricing", ctx, () -> ports.pricing().priceForSku(request));
        String unitPriceXml = xslt.transform(XSL_PRICE_TO_CANONICAL, erpXml.toXml(response));
        return canonical.unmarshalUnitPrice(unitPriceXml);
    }

    String orderStatus(SagaContext ctx) {
        GetOrderStatusRequest request = new GetOrderStatusRequest();
        request.setOrderId(ctx.getOrderId());
        GetOrderStatusResponse response = withRetry("confirm", ctx, () -> ports.orders().getOrderStatus(request));
        return response.getStatus() == null ? null : response.getStatus().value();
    }

    // ------------------------------------------------------------- resilience

    /**
     * Retry with exponential backoff on INFRA failures only; business faults
     * throw immediately (Sender class). Counts every real attempt (including
     * fault-injection attempts) into the saga attempts object.
     */
    <T> T withRetry(String step, SagaContext ctx, PortCall<T> call) {
        int maxAttempts = Math.max(1, retry.getMaxAttempts());
        long backoff = retry.getInitialBackoffMs();
        ErpInfraException lastInfra = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ctx.countAttempt(step);
            if (faultInjection.shouldFail(step, ctx.getFaultInjectionCommand())) {
                lastInfra = new ErpInfraException("synthetic timeout injected at step '" + step
                        + "' by the fault-injection hook");
            } else {
                try {
                    return call.call();
                } catch (Exception e) {
                    if (ErpErrorClassifier.isBusinessFault(e)) {
                        throw ErpErrorClassifier.toBusinessException(e);
                    }
                    lastInfra = new ErpInfraException(infraMessage(step, e), e);
                    LOG.warn("ERP call failed on step '{}' attempt {}/{}: {}", step, attempt, maxAttempts,
                            rootMessage(e));
                }
            }
            if (attempt < maxAttempts) {
                LOG.info("Retrying ERP step '{}' after {} ms (attempt {}/{})", step, backoff, attempt + 1, maxAttempts);
                sleep(backoff);
                backoff = Math.round(backoff * retry.getMultiplier());
            }
        }
        throw lastInfra != null ? lastInfra : new ErpInfraException("ERP call failed on step '" + step + "'");
    }

    /** Single classified attempt (best-effort compensation path). */
    <T> T once(PortCall<T> call) {
        try {
            return call.call();
        } catch (Exception e) {
            if (ErpErrorClassifier.isBusinessFault(e)) {
                throw ErpErrorClassifier.toBusinessException(e);
            }
            throw new ErpInfraException(infraMessage("release", e), e);
        }
    }

    // ----------------------------------------------------------------- helpers

    private LegacyAuditType auditOf(SagaContext ctx) {
        return erpXml.audit(ctx.getOrder().getAudit().getSourceSystem(),
                ctx.getOrder().getAudit().getReceivedAt(),
                ctx.getOrder().getAudit().getCorrelationId());
    }

    private static String infraMessage(String step, Exception e) {
        String kind = ErpErrorClassifier.isTransportFailure(e)
                ? (rootCause(e) instanceof java.net.SocketTimeoutException ? "timeout" : "connect failure")
                : "transport failure";
        return "ERP " + kind + " on step '" + step + "': " + rootMessage(e);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = rootCause(t);
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErpInfraException("Interrupted while backing off between ERP retries", e);
        }
    }
}

package com.mapleretail.silkroute.esb.erp;

import com.mapleretail.silkroute.esb.saga.SagaContext;

/**
 * One mediated ERP operation handed to the gateway bean on the
 * circuit-breaker route. The gateway dispatches on {@link Op} and always
 * answers with an {@link ErpCallResult} for business faults; infra failures
 * propagate as {@link ErpInfraException} so the circuit breaker records them.
 */
public final class ErpCall {

    public enum Op {
        SUBMIT_ORDER, RESERVE, RELEASE, PRICE, GET_ORDER_STATUS
    }

    private final Op op;
    private final SagaContext ctx;
    private final String skuId;
    private final Integer quantity;
    private final Integer lineIndex;
    private final String reservationId;

    private ErpCall(Op op, SagaContext ctx, String skuId, Integer quantity, Integer lineIndex, String reservationId) {
        this.op = op;
        this.ctx = ctx;
        this.skuId = skuId;
        this.quantity = quantity;
        this.lineIndex = lineIndex;
        this.reservationId = reservationId;
    }

    public static ErpCall submitOrder(SagaContext ctx) {
        return new ErpCall(Op.SUBMIT_ORDER, ctx, null, null, null, null);
    }

    public static ErpCall reserve(SagaContext ctx, String skuId, int quantity, int lineIndex) {
        return new ErpCall(Op.RESERVE, ctx, skuId, quantity, lineIndex, null);
    }

    public static ErpCall release(SagaContext ctx, String reservationId) {
        return new ErpCall(Op.RELEASE, ctx, null, null, null, reservationId);
    }

    public static ErpCall price(SagaContext ctx, int lineIndex) {
        return new ErpCall(Op.PRICE, ctx, null, null, lineIndex, null);
    }

    public static ErpCall orderStatus(SagaContext ctx) {
        return new ErpCall(Op.GET_ORDER_STATUS, ctx, null, null, null, null);
    }

    public Op getOp() {
        return op;
    }

    public SagaContext getCtx() {
        return ctx;
    }

    public String getSkuId() {
        return skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getLineIndex() {
        return lineIndex;
    }

    public String getReservationId() {
        return reservationId;
    }
}

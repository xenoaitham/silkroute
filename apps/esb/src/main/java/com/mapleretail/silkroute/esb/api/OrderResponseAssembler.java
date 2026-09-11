package com.mapleretail.silkroute.esb.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;
import com.mapleretail.silkroute.esb.events.PiiMaskingPolicy;
import com.mapleretail.silkroute.esb.saga.SagaContext;

/**
 * Assembles the shared-contract success body (HTTP 201 / event payload) from
 * the saga state. Money is carried as integer minor units + currency (C5).
 */
@Component
public class OrderResponseAssembler {

    private final PiiMaskingPolicy masking;

    public OrderResponseAssembler(PiiMaskingPolicy masking) {
        this.masking = masking;
    }

    public OrderSubmissionResponse assemble(SagaContext ctx, CanonicalConfirmedOrder confirmed,
            List<CanonicalUnitPrice> unitPrices) {
        OrderSubmissionResponse response = new OrderSubmissionResponse();
        response.setOrderId(confirmed.getOrderId());
        response.setStatus(confirmed.getStatus());
        response.setExternalOrderRef(ctx.getOrder().getExternalOrderRef());
        response.setStoreId(ctx.getOrder().getStoreId());
        response.setRegion(ctx.getRegion().code());
        response.setChannel(ctx.getOrder().getChannel());
        response.setReservationId(ctx.getReservationIds().isEmpty() ? null : ctx.getReservationIds().get(0));

        if (confirmed.getTotalAmount() != null) {
            response.setTotalAmount(OrderSubmissionResponse.Money.of(
                    confirmed.getTotalAmount().getAmountMinor(), confirmed.getTotalAmount().getCurrency()));
        }
        List<OrderSubmissionResponse.UnitPrice> prices = new ArrayList<>();
        for (CanonicalUnitPrice price : unitPrices) {
            prices.add(OrderSubmissionResponse.UnitPrice.of(price.getSkuId(), price.getAmountMinor(),
                    price.getCurrency()));
        }
        response.setUnitPrices(prices);

        List<OrderSubmissionResponse.StepStatus> saga = new ArrayList<>();
        for (String step : ctx.getCompletedSteps()) {
            saga.add(OrderSubmissionResponse.StepStatus.completed(step));
        }
        response.setSaga(saga);
        response.setAttempts(ctx.getAttempts());

        response.getRoute().setRegion(ctx.getRegion().code());
        response.getRoute().setCustomerRefMasked(
                masking.applied(ctx.getOrder().getCustomerRef(), ctx.getRegion().code()));

        response.getAudit().setSourceSystem(ctx.getOrder().getAudit().getSourceSystem());
        response.getAudit().setCorrelationId(ctx.getOrder().getAudit().getCorrelationId());
        return response;
    }

    /** The customerRef value that may travel on the shared event topic (C1: masked for CN). */
    public String egressCustomerRef(SagaContext ctx) {
        return masking.maskForEgress(ctx.getOrder().getCustomerRef(), ctx.getRegion().code());
    }
}

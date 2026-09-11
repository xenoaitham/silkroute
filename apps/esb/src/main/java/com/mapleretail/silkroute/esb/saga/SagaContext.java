package com.mapleretail.silkroute.esb.saga;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;

/**
 * Mutable saga execution state threaded through the ERP gateway calls (it is
 * the body on the direct:erp circuit-breaker route).
 */
public final class SagaContext {

    private final CanonicalOrder order;
    private final Region region;
    private final String currency;
    private final String faultInjectionCommand;

    /** Real per-step call attempts (the retry-recovery proof). Always has all 4 keys. */
    private final Map<String, Integer> attempts = new LinkedHashMap<>();

    /** Steps completed in execution order. */
    private final List<String> completedSteps = new ArrayList<>();

    /** Reservation ids collected on the reserve step (compensation candidates). */
    private final List<String> reservationIds = new ArrayList<>();

    /** Reservation ids successfully released during compensation. */
    private final List<String> releasedReservationIds = new ArrayList<>();

    public SagaContext(CanonicalOrder order, Region region, String faultInjectionCommand) {
        this.order = order;
        this.region = region;
        this.currency = region.currency();
        this.faultInjectionCommand = faultInjectionCommand;
        for (String step : List.of("order", "reserve", "pricing", "confirm")) {
            attempts.put(step, 0);
        }
    }

    private String orderId;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public CanonicalOrder getOrder() {
        return order;
    }

    public Region getRegion() {
        return region;
    }

    public String getCurrency() {
        return currency;
    }

    public String getFaultInjectionCommand() {
        return faultInjectionCommand;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    public void countAttempt(String step) {
        attempts.merge(step, 1, Integer::sum);
    }

    public List<String> getCompletedSteps() {
        return completedSteps;
    }

    public void stepCompleted(String step) {
        completedSteps.add(step);
    }

    public List<String> getReservationIds() {
        return reservationIds;
    }

    public List<String> getReleasedReservationIds() {
        return releasedReservationIds;
    }
}

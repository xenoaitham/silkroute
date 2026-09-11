package com.mapleretail.silkroute.esb.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The HTTP 201 / success-event view of a confirmed order (the shared contract
 * body). customerRef is deliberately NOT part of the REST response; it is only
 * set (masked for CN, clear otherwise, C1) on the Kafka event copy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderSubmissionResponse {

    private String orderId;
    private String status;
    private String externalOrderRef;
    private String storeId;
    private String region;
    private String channel;
    private String reservationId;
    private Money totalAmount;
    private List<UnitPrice> unitPrices = new ArrayList<>();
    private List<StepStatus> saga = new ArrayList<>();
    /** Always present, counts real per-step call attempts (retry-recovery proof). */
    private Map<String, Integer> attempts = new LinkedHashMap<>();
    private RouteInfo route = new RouteInfo();
    private Audit audit = new Audit();
    /** Only populated on the Kafka event copy (masked for CN per C1). */
    private String customerRef;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Money {
        @JsonProperty("amountMinor")
        private Long amountMinor;
        @JsonProperty("currency")
        private String currency;

        public static Money of(Long amountMinor, String currency) {
            Money money = new Money();
            money.amountMinor = amountMinor;
            money.currency = currency;
            return money;
        }

        public Long getAmountMinor() {
            return amountMinor;
        }

        public String getCurrency() {
            return currency;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UnitPrice {
        @JsonProperty("skuId")
        private String skuId;
        @JsonProperty("amountMinor")
        private Long amountMinor;
        @JsonProperty("currency")
        private String currency;

        public static UnitPrice of(String skuId, Long amountMinor, String currency) {
            UnitPrice price = new UnitPrice();
            price.skuId = skuId;
            price.amountMinor = amountMinor;
            price.currency = currency;
            return price;
        }

        public String getSkuId() {
            return skuId;
        }

        public Long getAmountMinor() {
            return amountMinor;
        }

        public String getCurrency() {
            return currency;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepStatus {
        @JsonProperty("step")
        private String step;
        @JsonProperty("status")
        private String status;

        public static StepStatus completed(String step) {
            StepStatus s = new StepStatus();
            s.step = step;
            s.status = "COMPLETED";
            return s;
        }

        public String getStep() {
            return step;
        }

        public String getStatus() {
            return status;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RouteInfo {
        @JsonProperty("region")
        private String region;
        /** C1 hook outcome: true when the masking policy masked customerRef on shared-topic egress. */
        @JsonProperty("customerRefMasked")
        private boolean customerRefMasked;

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isCustomerRefMasked() {
            return customerRefMasked;
        }

        public void setCustomerRefMasked(boolean customerRefMasked) {
            this.customerRefMasked = customerRefMasked;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Audit {
        @JsonProperty("sourceSystem")
        private String sourceSystem;
        @JsonProperty("correlationId")
        private String correlationId;

        public String getSourceSystem() {
            return sourceSystem;
        }

        public void setSourceSystem(String sourceSystem) {
            this.sourceSystem = sourceSystem;
        }

        public String getCorrelationId() {
            return correlationId;
        }

        public void setCorrelationId(String correlationId) {
            this.correlationId = correlationId;
        }
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExternalOrderRef() {
        return externalOrderRef;
    }

    public void setExternalOrderRef(String externalOrderRef) {
        this.externalOrderRef = externalOrderRef;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Money totalAmount) {
        this.totalAmount = totalAmount;
    }

    public List<UnitPrice> getUnitPrices() {
        return unitPrices;
    }

    public void setUnitPrices(List<UnitPrice> unitPrices) {
        this.unitPrices = unitPrices;
    }

    public List<StepStatus> getSaga() {
        return saga;
    }

    public void setSaga(List<StepStatus> saga) {
        this.saga = saga;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    public void setAttempts(Map<String, Integer> attempts) {
        this.attempts = attempts;
    }

    public RouteInfo getRoute() {
        return route;
    }

    public void setRoute(RouteInfo route) {
        this.route = route;
    }

    public Audit getAudit() {
        return audit;
    }

    public void setAudit(Audit audit) {
        this.audit = audit;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }
}

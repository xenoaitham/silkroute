package com.mapleretail.silkroute.esb.events;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Canonical failure event published to the shared DLQ topic
 * (silkroute.esb.dlq) after retries are exhausted (infra) or a compensated saga.
 * The customerRef field is masked per C1 when region=CN (enforced by the
 * constructor — the masked value must be supplied through PiiMaskingPolicy).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DlqPayload {

    @JsonProperty("eventType")
    private final String eventType = "ORDER_SAGA_FAILED";

    @JsonProperty("orderId")
    private String orderId;
    @JsonProperty("externalOrderRef")
    private String externalOrderRef;
    @JsonProperty("sourceSystem")
    private String sourceSystem;
    @JsonProperty("storeId")
    private String storeId;
    @JsonProperty("region")
    private String region;
    /** masked per C1 when region=CN */
    @JsonProperty("customerRef")
    private String customerRef;
    @JsonProperty("correlationId")
    private String correlationId;
    @JsonProperty("failedStep")
    private String failedStep;
    @JsonProperty("attempts")
    private Map<String, Integer> attempts;
    @JsonProperty("errorCode")
    private String errorCode;
    @JsonProperty("category")
    private String category;
    @JsonProperty("compensated")
    private Boolean compensated;
    @JsonProperty("releasedReservationIds")
    private List<String> releasedReservationIds;
    @JsonProperty("message")
    private String message;
    @JsonProperty("occurredAt")
    private String occurredAt;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getExternalOrderRef() {
        return externalOrderRef;
    }

    public void setExternalOrderRef(String externalOrderRef) {
        this.externalOrderRef = externalOrderRef;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
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

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getFailedStep() {
        return failedStep;
    }

    public void setFailedStep(String failedStep) {
        this.failedStep = failedStep;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    public void setAttempts(Map<String, Integer> attempts) {
        this.attempts = attempts;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getCompensated() {
        return compensated;
    }

    public void setCompensated(Boolean compensated) {
        this.compensated = compensated;
    }

    public List<String> getReleasedReservationIds() {
        return releasedReservationIds;
    }

    public void setReleasedReservationIds(List<String> releasedReservationIds) {
        this.releasedReservationIds = releasedReservationIds;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(String occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getEventType() {
        return eventType;
    }
}

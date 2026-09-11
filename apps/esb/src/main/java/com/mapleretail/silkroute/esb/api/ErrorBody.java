package com.mapleretail.silkroute.esb.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Canonical error body (shared contract):
 * {"error":{"code","category","message","failedStep","region","correlationId",
 *  "attempts"[,"orderId" for DUPLICATE][,"compensated","releasedReservationId"
 *  when the saga compensated]}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorBody {

    @JsonProperty("code")
    private String code;
    @JsonProperty("category")
    private String category;
    @JsonProperty("message")
    private String message;
    @JsonProperty("failedStep")
    private String failedStep;
    @JsonProperty("region")
    private String region;
    @JsonProperty("correlationId")
    private String correlationId;
    @JsonProperty("attempts")
    private Map<String, Integer> attempts;
    @JsonProperty("orderId")
    private String orderId;
    @JsonProperty("compensated")
    private Boolean compensated;
    @JsonProperty("releasedReservationId")
    private String releasedReservationId;

    public static ErrorBody of(String code, String category, String message, String failedStep, String region,
            String correlationId, Map<String, Integer> attempts) {
        ErrorBody body = new ErrorBody();
        body.code = code;
        body.category = category;
        body.message = message;
        body.failedStep = failedStep;
        body.region = region;
        body.correlationId = correlationId;
        body.attempts = attempts == null ? new LinkedHashMap<>() : attempts;
        return body;
    }

    public String getCode() {
        return code;
    }

    public String getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    public String getFailedStep() {
        return failedStep;
    }

    public String getRegion() {
        return region;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Boolean getCompensated() {
        return compensated;
    }

    public void setCompensated(Boolean compensated) {
        this.compensated = compensated;
    }

    public String getReleasedReservationId() {
        return releasedReservationId;
    }

    public void setReleasedReservationId(String releasedReservationId) {
        this.releasedReservationId = releasedReservationId;
    }
}

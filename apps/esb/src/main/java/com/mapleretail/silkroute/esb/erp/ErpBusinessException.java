package com.mapleretail.silkroute.esb.erp;

/**
 * ERP business fault translated to canonical semantics (ADR-0003): the ERP
 * writes soap:Receiver as the wire fault code for these, but a typed fault
 * DETAIL element (ErpFaultType hierarchy) always means the CALLER's fault →
 * SENDER/BUSINESS class. Never retried, never trips the circuit breaker.
 */
public final class ErpBusinessException extends RuntimeException {

    private final String errorCode;
    private final String sourceSubsystem;
    private final String occurredAt;

    public ErpBusinessException(String errorCode, String message, String sourceSubsystem, String occurredAt) {
        super(message);
        this.errorCode = errorCode;
        this.sourceSubsystem = sourceSubsystem;
        this.occurredAt = occurredAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSourceSubsystem() {
        return sourceSubsystem;
    }

    public String getOccurredAt() {
        return occurredAt;
    }
}

package com.mapleretail.silkroute.esb.erp;

/**
 * Gateway answer for one mediated ERP operation. Business faults (ADR-0003:
 * Sender class) are RETURNED so they never cross the circuit-breaker boundary;
 * infra failures propagate as ErpInfraException and are recorded by the breaker.
 */
public final class ErpCallResult {

    private final Object value;
    private final ErpBusinessException businessError;

    private ErpCallResult(Object value, ErpBusinessException businessError) {
        this.value = value;
        this.businessError = businessError;
    }

    public static ErpCallResult success(Object value) {
        return new ErpCallResult(value, null);
    }

    public static ErpCallResult business(ErpBusinessException error) {
        return new ErpCallResult(null, error);
    }

    public boolean isBusinessError() {
        return businessError != null;
    }

    public ErpBusinessException getBusinessError() {
        return businessError;
    }

    public Object getValue() {
        return value;
    }

    @SuppressWarnings("unchecked")
    public <T> T valueAs(Class<T> type) {
        if (value == null) {
            throw new IllegalStateException("No success value present (business error: "
                    + (businessError == null ? "none" : businessError.getErrorCode()) + ")");
        }
        return type.cast(value);
    }
}

package com.mapleretail.silkroute.esb.erp;

/**
 * Raised when the resilience4j circuit breaker refuses the ERP call (state
 * OPEN, or HALF-OPEN probe exhausted): mapped to 503 CIRCUIT-OPEN — fail fast,
 * the wire is never touched.
 */
public final class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String message) {
        super(message);
    }
}

package com.mapleretail.silkroute.esb.erp;

/**
 * Infrastructure-class failure of an ERP call: connect refused / timeout /
 * unexpected transport or security failure (Receiver class per ADR-0003).
 * Eligible for retry (timeout/connect only) and it is what the resilience4j
 * circuit breaker records.
 */
public final class ErpInfraException extends RuntimeException {

    public ErpInfraException(String message) {
        super(message);
    }

    public ErpInfraException(String message, Throwable cause) {
        super(message, cause);
    }
}

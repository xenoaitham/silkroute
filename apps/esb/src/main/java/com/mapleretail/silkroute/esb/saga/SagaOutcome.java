package com.mapleretail.silkroute.esb.saga;

/**
 * Outcome of one saga run: the HTTP status + JSON body to render. Events are
 * published inside the orchestrator before the outcome is returned.
 */
public record SagaOutcome(int httpStatus, String responseJson) {

    public boolean isSuccess() {
        return httpStatus == 201;
    }
}

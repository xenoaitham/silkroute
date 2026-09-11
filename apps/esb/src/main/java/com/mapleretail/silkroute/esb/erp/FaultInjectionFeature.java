package com.mapleretail.silkroute.esb.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fault-injection test hook (QA-facing, implemented EXACTLY per contract):
 * when ESB_FAULT_INJECTION=true, request header X-Fault-Injection with value
 * "fail-pricing" or "fail-confirm" makes that saga step throw a synthetic infra
 * error (as if the call timed out) on EVERY attempt — driving retry →
 * exhaustion → compensation → DLQ deterministically. When the flag is absent or
 * false, the header is IGNORED.
 */
public final class FaultInjectionFeature {

    public static final String HEADER = "X-Fault-Injection";

    private static final Logger LOG = LoggerFactory.getLogger(FaultInjectionFeature.class);

    private final boolean enabled;

    public FaultInjectionFeature(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when the given saga step must throw the synthetic infra error. */
    public boolean shouldFail(String step, String command) {
        if (!enabled || command == null || command.isBlank()) {
            return false;
        }
        boolean hit = command.equalsIgnoreCase("fail-" + step);
        if (hit) {
            LOG.warn("FAULT INJECTION: synthetic infra timeout injected at step '{}'", step);
        }
        return hit;
    }
}

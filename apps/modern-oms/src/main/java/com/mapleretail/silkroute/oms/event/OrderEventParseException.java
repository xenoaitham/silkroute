package com.mapleretail.silkroute.oms.event;

/**
 * A malformed (poison) event: unparseable JSON or a semantically invalid body
 * (missing business keys, non-positive quantity, missing line price). The
 * consumer loop logs OMS-POISON and acknowledges these — never a crash loop.
 */
public class OrderEventParseException extends Exception {

    public OrderEventParseException(String message) {
        super(message);
    }

    public OrderEventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

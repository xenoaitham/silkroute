package com.mapleretail.silkroute.legacyerp.wss;

/**
 * Property keys for the WSS4J in-interceptor configuration map. WSS4J 3.x removed
 * the old WSHandlerConstants string constants for these; the values below are the
 * keys WSS4J's WSHandler base class actually reads (action / passwordType /
 * passwordCallbackRef) and the UsernameToken action token.
 */
public final class ErpWssPropertyKeys {

    public static final String ACTION = "action";
    public static final String PASSWORD_TYPE = "passwordType";
    public static final String PASSWORD_CALLBACK_REF = "passwordCallbackRef";
    public static final String USERNAME_TOKEN_ACTION = "UsernameToken";

    private ErpWssPropertyKeys() {
    }
}

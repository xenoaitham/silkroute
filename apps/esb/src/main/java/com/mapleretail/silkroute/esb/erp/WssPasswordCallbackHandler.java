package com.mapleretail.silkroute.esb.erp;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.ext.WSPasswordCallback;

/**
 * Supplies the WSS UsernameToken password to WSS4J on the outbound path. The
 * password itself comes from env-indirected sim config (${ERP_WSS_PASSWORD}) —
 * it is never logged.
 */
public final class WssPasswordCallbackHandler implements CallbackHandler {

    private final String password;

    public WssPasswordCallbackHandler(String password) {
        this.password = password;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof WSPasswordCallback passwordCallback) {
                passwordCallback.setPassword(password);
            } else {
                throw new UnsupportedCallbackException(callback);
            }
        }
    }
}

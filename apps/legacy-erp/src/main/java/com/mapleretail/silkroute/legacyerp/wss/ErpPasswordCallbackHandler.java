package com.mapleretail.silkroute.legacyerp.wss;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.wss4j.common.ext.WSPasswordCallback;

/**
 * Supplies the expected password to WSS4J's UsernameToken validator (PasswordText
 * mode: WSS4J compares what the caller sent against what this handler returns).
 * Unknown usernames fail here; wrong passwords fail inside WSS4J's validator —
 * both surface as SOAP security faults, and the username (never the password) is
 * logged once per failure by {@link AuditingWss4jInInterceptor}.
 */
public final class ErpPasswordCallbackHandler implements CallbackHandler {

    private final ErpWssCredentials credentials;

    public ErpPasswordCallbackHandler(ErpWssCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof WSPasswordCallback passwordCallback
                    && passwordCallback.getUsage() == WSPasswordCallback.USERNAME_TOKEN) {
                char[] expected = credentials.passwordFor(passwordCallback.getIdentifier());
                if (expected == null) {
                    throw new IOException("No WSS credential registered for the presented username");
                }
                passwordCallback.setPassword(new String(expected));
            } else {
                throw new UnsupportedCallbackException(callback, "Unsupported callback: " + callback);
            }
        }
    }
}

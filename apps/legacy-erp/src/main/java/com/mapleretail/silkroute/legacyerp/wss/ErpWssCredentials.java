package com.mapleretail.silkroute.legacyerp.wss;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single WS-Security client identity accepted by the ERP sim. Values resolve
 * from the environment with sim-dummy defaults (same ${VAR:-default} indirection
 * policy as the sim compose stack); the password is never logged and never leaves
 * this class.
 */
@Component
public final class ErpWssCredentials {

    private final String username;
    private final String password;

    public ErpWssCredentials(@Value("${erp.wss.username}") String username,
                             @Value("${erp.wss.password}") String password) {
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    /** Returns the expected password for the presented username, or null if unknown. */
    public char[] passwordFor(String presentedUsername) {
        return username.equals(presentedUsername) ? password.toCharArray() : null;
    }

    @Override
    public String toString() {
        // Deliberately excludes the password (C5-adjacent hygiene: secrets never in logs).
        return "ErpWssCredentials[username=" + username + "]";
    }
}

package com.mapleretail.silkroute.esb.saga;

import java.util.Locale;

/**
 * Region derivation (load-bearing for C1/PIPL routing): storeId prefix
 * ST-CA-* → CA/CAD, ST-SG-* → SG/SGD, ST-CN-* → CN/CNY. The region decides the
 * pricing currency and the masking egress policy.
 */
public enum Region {

    CA("CAD"),
    SG("SGD"),
    CN("CNY");

    private final String currency;

    Region(String currency) {
        this.currency = currency;
    }

    public String code() {
        return name();
    }

    /** ISO currency code for the region (C5: CAD/SGD/CNY). */
    public String currency() {
        return currency;
    }

    public static Region fromStoreId(String storeId) {
        if (storeId == null || storeId.length() < 8 || !storeId.toUpperCase(Locale.ROOT).startsWith("ST-")) {
            throw new IllegalArgumentException("storeId does not match ST-(CA|SG|CN)-nn: " + storeId);
        }
        return switch (storeId.substring(3, 5).toUpperCase(Locale.ROOT)) {
            case "CA" -> CA;
            case "SG" -> SG;
            case "CN" -> CN;
            default -> throw new IllegalArgumentException("Unknown store region prefix: " + storeId);
        };
    }

    public static boolean isValidStoreId(String storeId) {
        try {
            fromStoreId(storeId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

package com.mapleretail.silkroute.esb.canonical.order.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Money per C5: integer minor units (12999 = 129.99) + explicit CAD/SGD/CNY
 * code. Floats are forbidden anywhere in this estate.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "CanonicalMoney", namespace = CanonicalOrder.NS, propOrder = { "amountMinor", "currency" })
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalMoney {

    /** Minor units as a signed 64-bit integer — never a float. */
    @XmlElement(name = "amountMinor", required = true, namespace = CanonicalOrder.NS)
    private Long amountMinor;

    @XmlElement(name = "currency", required = true, namespace = CanonicalOrder.NS)
    private String currency;

    public CanonicalMoney() {
    }

    public CanonicalMoney(Long amountMinor, String currency) {
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(Long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}

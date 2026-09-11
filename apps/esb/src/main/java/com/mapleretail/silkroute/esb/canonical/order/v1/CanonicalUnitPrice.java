package com.mapleretail.silkroute.esb.canonical.order.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Unit price for one SKU (output of the pricing XSLT leg). Canonical XML root:
 * {urn:maple:canonical:order:v1}unitPrice.
 */
@XmlRootElement(name = "unitPrice", namespace = CanonicalOrder.NS)
@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalUnitPrice {

    @XmlElement(name = "skuId", required = true, namespace = CanonicalOrder.NS)
    private String skuId;

    @XmlElement(name = "amountMinor", required = true, namespace = CanonicalOrder.NS)
    private Long amountMinor;

    @XmlElement(name = "currency", required = true, namespace = CanonicalOrder.NS)
    private String currency;

    public CanonicalUnitPrice() {
    }

    public CanonicalUnitPrice(String skuId, Long amountMinor, String currency) {
        this.skuId = skuId;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
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

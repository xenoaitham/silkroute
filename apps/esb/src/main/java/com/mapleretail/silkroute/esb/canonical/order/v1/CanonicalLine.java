package com.mapleretail.silkroute.esb.canonical.order.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * One order line. quantity is a plain integer (1..999 per schema) — the ERP
 * contract uses xsd:int as well.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "CanonicalLine", namespace = CanonicalOrder.NS, propOrder = { "skuId", "quantity" })
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalLine {

    @XmlElement(name = "skuId", required = true, namespace = CanonicalOrder.NS)
    private String skuId;

    @XmlElement(name = "quantity", required = true, namespace = CanonicalOrder.NS)
    private Integer quantity;

    public CanonicalLine() {
    }

    public CanonicalLine(String skuId, Integer quantity) {
        this.skuId = skuId;
        this.quantity = quantity;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}

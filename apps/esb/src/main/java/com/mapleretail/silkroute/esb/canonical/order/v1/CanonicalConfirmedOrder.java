package com.mapleretail.silkroute.esb.canonical.order.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Canonical confirmed order — what the ERP submit leg produced, mediated back to
 * canonical form by submit-response-to-canonical.xsl. The REST/event view adds
 * saga, attempts and route data on top (see api/OrderSubmissionResponse).
 */
@XmlRootElement(name = "confirmedOrder", namespace = CanonicalOrder.NS)
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(namespace = CanonicalOrder.NS, propOrder = {
        "orderId", "status", "externalOrderRef", "storeId", "channel", "submittedAt", "lineCount", "totalAmount" })
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalConfirmedOrder {

    @XmlElement(name = "orderId", required = true, namespace = CanonicalOrder.NS)
    private String orderId;

    @XmlElement(name = "status", required = true, namespace = CanonicalOrder.NS)
    private String status;

    @XmlElement(name = "externalOrderRef", required = true, namespace = CanonicalOrder.NS)
    private String externalOrderRef;

    /** Not carried by the frozen submitOrderResponse — supplied as an XSLT parameter from the request context. */
    @XmlElement(name = "storeId", required = true, namespace = CanonicalOrder.NS)
    private String storeId;

    @XmlElement(name = "channel", required = true, namespace = CanonicalOrder.NS)
    private String channel;

    @XmlElement(name = "submittedAt", namespace = CanonicalOrder.NS)
    private String submittedAt;

    @XmlElement(name = "lineCount", namespace = CanonicalOrder.NS)
    private Integer lineCount;

    @XmlElement(name = "totalAmount", required = true, namespace = CanonicalOrder.NS)
    private CanonicalMoney totalAmount;

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExternalOrderRef() {
        return externalOrderRef;
    }

    public void setExternalOrderRef(String externalOrderRef) {
        this.externalOrderRef = externalOrderRef;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(String submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Integer getLineCount() {
        return lineCount;
    }

    public void setLineCount(Integer lineCount) {
        this.lineCount = lineCount;
    }

    public CanonicalMoney getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(CanonicalMoney totalAmount) {
        this.totalAmount = totalAmount;
    }
}

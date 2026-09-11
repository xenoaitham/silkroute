package com.mapleretail.silkroute.esb.canonical.order.v1;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Canonical inbound order (the body of POST /api/v1/orders after schema
 * validation). Canonical XML root: {urn:maple:canonical:order:v1}order.
 */
@XmlRootElement(name = "order", namespace = CanonicalOrder.NS)
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "CanonicalOrder", namespace = CanonicalOrder.NS, propOrder = {
        "externalOrderRef", "sourceSystem", "storeId", "customerRef", "channel", "lines", "audit" })
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalOrder {

    public static final String NS = "urn:maple:canonical:order:v1";

    @XmlElement(name = "externalOrderRef", required = true)
    private String externalOrderRef;

    @XmlElement(name = "sourceSystem", required = true)
    private String sourceSystem;

    @XmlElement(name = "storeId", required = true)
    private String storeId;

    /** Customer PII (C1): never egresses unmasked to shared topics for CN region. */
    @XmlElement(name = "customerRef")
    private String customerRef;

    @XmlElement(name = "channel", required = true)
    private String channel;

    @XmlElementWrapper(name = "lines", required = true)
    @XmlElement(name = "line", required = true)
    private List<CanonicalLine> lines = new ArrayList<>();

    @XmlElement(name = "audit", required = true)
    private CanonicalAudit audit;

    public String getExternalOrderRef() {
        return externalOrderRef;
    }

    public void setExternalOrderRef(String externalOrderRef) {
        this.externalOrderRef = externalOrderRef;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public List<CanonicalLine> getLines() {
        return lines;
    }

    public void setLines(List<CanonicalLine> lines) {
        this.lines = lines;
    }

    public CanonicalAudit getAudit() {
        return audit;
    }

    public void setAudit(CanonicalAudit audit) {
        this.audit = audit;
    }
}

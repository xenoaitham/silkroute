package com.mapleretail.silkroute.esb.canonical.order.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Audit block mirroring the frozen LegacyAuditType (urn:maple:erp:common:v1):
 * correlation ids are preserved end-to-end (source system -> ERP -> events).
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "CanonicalAudit", namespace = CanonicalOrder.NS, propOrder = { "sourceSystem", "receivedAt", "correlationId" })
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalAudit {

    @XmlElement(name = "sourceSystem", required = true, namespace = CanonicalOrder.NS)
    private String sourceSystem;

    /** xsd:dateTime (date-time asserted by the JSON schema). */
    @XmlElement(name = "receivedAt", required = true, namespace = CanonicalOrder.NS)
    private String receivedAt;

    @XmlElement(name = "correlationId", required = true, namespace = CanonicalOrder.NS)
    private String correlationId;

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(String receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}

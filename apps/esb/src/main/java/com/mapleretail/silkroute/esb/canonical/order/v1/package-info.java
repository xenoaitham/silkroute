/**
 * ESB-OWNED canonical order model, version urn:maple:canonical:order:v1
 * (constraint C6: the ERP contracts are frozen; all impedance mismatch lives in
 * the ESB layer). The JSON Schema twin lives at
 * canonical/order/v1/order.schema.json; the XSLT legs under xslt/ map between
 * this namespace and the frozen ERP namespaces.
 *
 * Classes are dual-annotated: JAXB for the canonical XML representation used by
 * the XSLT legs, Jackson for the wire JSON. Money is integer minor units + ISO
 * currency code (C5) — never floats.
 */
@jakarta.xml.bind.annotation.XmlSchema(namespace = com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder.NS, elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED)
package com.mapleretail.silkroute.esb.canonical.order.v1;

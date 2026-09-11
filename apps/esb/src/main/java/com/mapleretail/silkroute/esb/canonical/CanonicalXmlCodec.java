package com.mapleretail.silkroute.esb.canonical;

import java.io.StringReader;
import java.io.StringWriter;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalConfirmedOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalOrder;
import com.mapleretail.silkroute.esb.canonical.order.v1.CanonicalUnitPrice;

/**
 * JAXB codec for the ESB-OWNED canonical model (urn:maple:canonical:order:v1).
 * JAXBContext is thread-safe; Marshaller/Unmarshaller are created per call.
 */
public final class CanonicalXmlCodec {

    private final JAXBContext context;

    public CanonicalXmlCodec() {
        try {
            this.context = JAXBContext.newInstance(CanonicalOrder.class, CanonicalConfirmedOrder.class,
                    CanonicalUnitPrice.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot build canonical JAXB context", e);
        }
    }

    public String marshal(Object canonical) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            marshaller.marshal(canonical, out);
            return out.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot marshal canonical model: " + e.getMessage(), e);
        }
    }

    public CanonicalOrder unmarshalOrder(String xml) {
        try {
            return (CanonicalOrder) context.createUnmarshaller().unmarshal(new StringReader(xml));
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot unmarshal canonical order: " + e.getMessage(), e);
        }
    }

    public CanonicalConfirmedOrder unmarshalConfirmedOrder(String xml) {
        try {
            return (CanonicalConfirmedOrder) context.createUnmarshaller().unmarshal(new StringReader(xml));
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot unmarshal canonical confirmed order: " + e.getMessage(), e);
        }
    }

    public CanonicalUnitPrice unmarshalUnitPrice(String xml) {
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Object parsed = unmarshaller.unmarshal(new StringReader(xml));
            if (parsed instanceof CanonicalUnitPrice price) {
                return price;
            }
            throw new IllegalStateException(
                    "Canonical unitPrice expected, got: " + (parsed == null ? "null" : parsed.getClass().getName()));
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot unmarshal canonical unit price: " + e.getMessage(), e);
        }
    }
}

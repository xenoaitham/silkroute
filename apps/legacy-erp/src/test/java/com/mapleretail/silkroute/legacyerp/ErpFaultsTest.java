package com.mapleretail.silkroute.legacyerp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.legacyerp.contract.orders.InvalidOrderFault;
import com.mapleretail.silkroute.legacyerp.fault.ErpFaults;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;

/**
 * Wire fidelity of the typed fault factory. The contract's invalidValue is
 * required-but-nillable: an absent value must marshal as xsi:nil="true", not
 * as an empty string that would masquerade as the offending value.
 */
class ErpFaultsTest {

    @Test
    void absentInvalidValueMarshalsAsXsiNil() throws Exception {
        maple.erp.orders.v1.InvalidOrderFault exception = ErpFaults.invalidOrder(
                ErpFaults.CODE_ORDER_MISSING_AUDIT, "audit block is required", "audit", null, null);
        InvalidOrderFault detail = exception.getFaultInfo();
        assertNull(detail.getInvalidValue());

        Marshaller marshaller = JAXBContext.newInstance(InvalidOrderFault.class).createMarshaller();
        StringWriter out = new StringWriter();
        marshaller.marshal(detail, new javax.xml.transform.stream.StreamResult(out));
        String wire = out.toString();

        assertTrue(wire.contains("xsi:nil=\"true\""),
                "nillable invalidValue absent -> xsi:nil on the wire, got: " + wire);
        // The element must be present (required), not omitted.
        assertTrue(wire.contains("invalidValue"), "required element must be present");
    }

    @Test
    void presentInvalidValueMarshalsItsText() throws Exception {
        maple.erp.orders.v1.InvalidOrderFault exception = ErpFaults.invalidOrder(
                ErpFaults.CODE_ORDER_UNKNOWN_STORE, "Unknown storeId: ST-XX-99", "storeId", "ST-XX-99", null);
        InvalidOrderFault detail = exception.getFaultInfo();
        assertEquals("ST-XX-99", detail.getInvalidValue());

        Marshaller marshaller = JAXBContext.newInstance(InvalidOrderFault.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, false);
        StringWriter out = new StringWriter();
        marshaller.marshal(detail, new javax.xml.transform.stream.StreamResult(out));
        String wire = out.toString();

        assertTrue(wire.contains(">ST-XX-99</"), "present value must marshal as text");
        assertEquals(-1, wire.indexOf("nil="), "a present value must never be nill");
    }
}

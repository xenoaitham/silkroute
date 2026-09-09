package com.mapleretail.silkroute.legacyerp.time;

import java.time.Instant;
import java.time.LocalDate;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Converts java.time values to the xsd:dateTime / xsd:date shapes the frozen
 * contracts use. All timestamps are emitted timezone-explicit (UTC) — constraint C5
 * requires timezone-explicit behavior across the multi-timezone batch windows.
 */
public final class XmlDateTimes {

    private static final DatatypeFactory DATATYPE_FACTORY = DatatypeFactoryHolder.FACTORY;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private XmlDateTimes() {
    }

    public static XMLGregorianCalendar nowUtc() {
        return utcTimestamp(Instant.now());
    }

    public static XMLGregorianCalendar utcTimestamp(Instant instant) {
        GregorianCalendar calendar = new GregorianCalendar(UTC);
        calendar.setTimeInMillis(instant.toEpochMilli());
        return DATATYPE_FACTORY.newXMLGregorianCalendar(calendar);
    }

    public static XMLGregorianCalendar utcDate(LocalDate date) {
        return DATATYPE_FACTORY.newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
    }

    private static final class DatatypeFactoryHolder {
        private static final DatatypeFactory FACTORY = createFactory();

        private static DatatypeFactory createFactory() {
            try {
                return DatatypeFactory.newInstance();
            } catch (javax.xml.datatype.DatatypeConfigurationException e) {
                throw new IllegalStateException("No XML DatatypeFactory available", e);
            }
        }
    }
}

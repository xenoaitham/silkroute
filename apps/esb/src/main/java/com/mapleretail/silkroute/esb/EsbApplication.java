package com.mapleretail.silkroute.esb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mapleretail.silkroute.esb.config.EsbProperties;

/**
 * SILKROUTE Phase 2 — Maple Retail Group (fictional) integration hub.
 *
 * Apache Camel (Spring Boot) ESB: REST facade on platform-http (loopback only),
 * hand-rolled saga to the FROZEN SOAP ERP estate, XSLT mediation legs, retry +
 * resilience4j circuit breaker + Kafka DLQ, Redis idempotent consumer, and the
 * C1 PII-masking egress hook for CN-region customer data.
 */
@SpringBootApplication
@EnableConfigurationProperties(EsbProperties.class)
public class EsbApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsbApplication.class, args);
    }
}

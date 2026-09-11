package com.mapleretail.silkroute.esb.config;

import java.time.Duration;

import com.mapleretail.silkroute.esb.api.OrderRequestProcessor;
import com.mapleretail.silkroute.esb.erp.ErpBusinessException;
import com.mapleretail.silkroute.esb.erp.ErpGateway;
import com.mapleretail.silkroute.esb.erp.ErpInfraException;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.Resilience4jConfigurationDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * Resilience wiring: ONE resilience4j circuit breaker (camel-resilience4j)
 * shared by every ERP gateway call (they all funnel through the single
 * direct:erp route), configured per the acceptance contract:
 *
 *   failureRateThreshold 50, slidingWindowSize 10, minimumNumberOfCalls 6,
 *   waitDurationInOpenState 2s, permittedNumberOfCallsInHalfOpenState 3.
 *
 * recordExceptions = ErpInfraException (timeouts / connect failures) ONLY —
 * ERP business faults (ADR-0003 Sender class) never open the breaker. When the
 * breaker is OPEN the call is refused before the wire (fail fast → 503
 * CIRCUIT-OPEN); healing upstream transitions OPEN → HALF_OPEN → CLOSED via
 * the permitted half-open probes.
 */
@Configuration
public class ResilienceConfiguration {

    public static final String CB_BEAN = "erpGatewayCircuitBreaker";

    @Bean
    public CircuitBreaker erpGatewayCircuitBreaker(EsbProperties properties) {
        EsbProperties.CircuitBreaker cfg = properties.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.getFailureRateThreshold())
                .slidingWindowSize(cfg.getSlidingWindowSize())
                .minimumNumberOfCalls(cfg.getMinimumNumberOfCalls())
                .waitDurationInOpenState(Duration.ofMillis(cfg.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cfg.getPermittedNumberOfCallsInHalfOpenState())
                .recordExceptions(ErpInfraException.class)
                .ignoreExceptions(ErpBusinessException.class)
                .build();
        return CircuitBreaker.of(CB_BEAN, config);
    }

    @Bean
    public RouteBuilder esbRoutes(OrderRequestProcessor orderRequestProcessor, ErpGateway erpGateway) {
        return new RouteBuilder() {
            @Override
            public void configure() {
                // REST facade: POST /api/v1/orders on the loopback servlet (server.address=127.0.0.1).
                from("platform-http:/api/v1/orders?httpMethodRestrict=POST")
                        .routeId("rest-create-order")
                        .process(orderRequestProcessor);

                // Single ERP gateway funnel wrapped by the shared circuit breaker.
                // throwExceptionWhenHalfOpenOrOpenState(true) is LOAD-BEARING: with the
                // default (false) an OPEN breaker would silently pass the exchange
                // through WITHOUT invoking the gateway — we need the CallNotPermitted
                // signal so the saga can fail fast with 503 CIRCUIT-OPEN.
                from("direct:erp")
                        .routeId("erp-gateway-circuit-breaker")
                        .circuitBreaker()
                        .resilience4jConfiguration(new Resilience4jConfigurationDefinition()
                                .circuitBreaker(CB_BEAN)
                                .throwExceptionWhenHalfOpenOrOpenState(true))
                        .bean(erpGateway, "dispatch")
                        .end();
            }
        };
    }
}

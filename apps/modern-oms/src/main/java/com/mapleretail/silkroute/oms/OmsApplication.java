package com.mapleretail.silkroute.oms;

import com.mapleretail.silkroute.oms.event.OrderEventListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * SILKROUTE the data plane - modern OMS event store (the CDC source of the data plane).
 *
 * Design (ADR-0006 decision 2): consume ESB success events
 * (silkroute.orders.events) into MySQL silkroute_oms (oms_order + oms_order_line)
 * with at-least-once semantics. The offset is acknowledged ONLY after the
 * writes succeed; a business-key guard (unique source_system + external_order_ref
 * plus INSERT ... ON DUPLICATE KEY UPDATE no-op) makes replays safe.
 *
 * ZERO listening ports (web-application-type=none) - the shared sim host gains
 * no new sockets. Money is integer minor units + currency everywhere (C5);
 * customerRef is stored verbatim (already ESB-masked for CN, C1).
 */
@SpringBootApplication
public class OmsApplication {

    private static final Logger LOG = LoggerFactory.getLogger(OmsApplication.class);

    public static void main(String[] args) {
        System.setProperty("spring.main.web-application-type", "none");
        SpringApplication.run(OmsApplication.class, args);
    }

    /** All knobs via ${VAR:default} indirection (ADR-0001); documented in README. */
    @Bean
    @ConfigurationProperties(prefix = "oms")
    OmsProperties omsProperties() {
        return new OmsProperties();
    }

    /**
     * Manual-ack container, built programmatically (not @KafkaListener) so the
     * start is explicit and the OMS-CONSUMER-START log line is emitted after
     * subscription - the readiness signal the Makefile wait loop greps for.
     */
    @Bean
    ConcurrentMessageListenerContainer<String, String> omsContainer(
            ConsumerFactory<String, String> consumerFactory,
            OrderEventListener listener,
            OmsProperties props) {
        ContainerProperties containerProps = new ContainerProperties(props.getTopic());
        containerProps.setGroupId(props.getConsumerGroup());
        containerProps.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        containerProps.setMessageListener(listener);
        containerProps.setDeliveryAttemptHeader(true);

        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, containerProps);
        // concurrency 1 keeps per-key ordering and keeps the consumer loop simple
        container.setConcurrency(props.getConcurrency());
        container.setAutoStartup(false);

        // A failed write (DB down) must NEVER be skipped: retry forever with a
        // fixed backoff - loud logs, loop alive, offset unacknowledged. Malformed
        // (poison) events never reach this path: the listener logs OMS-POISON and
        // acknowledges them explicitly.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(2_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        container.setCommonErrorHandler(errorHandler);
        return container;
    }

    @Bean
    ApplicationRunner startConsumer(ConcurrentMessageListenerContainer<String, String> container, OmsProperties props) {
        return (ApplicationArguments args) -> {
            container.start();
            LOG.info("OMS-CONSUMER-START group={} topic={} bootstrap={} concurrency={} autoOffsetReset={}",
                    props.getConsumerGroup(), props.getTopic(),
                    props.getBootstrapServers(), props.getConcurrency(), props.getAutoOffsetReset());
            LOG.info("OMS-READY db={} region-policy=customerRef-stored-verbatim(C1-masked-at-ESB)",
                    props.getDatasourceUrl());
        };
    }

    /** Typed view of the env-indirected knobs (all have sim-mode defaults). */
    public static class OmsProperties {
        private String topic;
        private String consumerGroup;
        private String bootstrapServers;
        private String autoOffsetReset;
        private int concurrency;
        private String datasourceUrl;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getAutoOffsetReset() {
            return autoOffsetReset;
        }

        public void setAutoOffsetReset(String autoOffsetReset) {
            this.autoOffsetReset = autoOffsetReset;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public String getDatasourceUrl() {
            return datasourceUrl;
        }

        public void setDatasourceUrl(String datasourceUrl) {
            this.datasourceUrl = datasourceUrl;
        }
    }
}

package com.mapleretail.silkroute.esb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the silkroute.* block in application.yml. Every value is
 * env-indirected (${VAR:default}) at the YAML layer — this class only holds the
 * resolved sim values.
 */
@ConfigurationProperties(prefix = "silkroute")
public class EsbProperties {

    private final Erp erp = new Erp();
    private final Kafka kafka = new Kafka();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Idempotency idempotency = new Idempotency();
    /** C1/PII test hook: only when true does X-Fault-Injection have any effect. */
    private boolean faultInjection;

    public Erp getErp() {
        return erp;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public boolean isFaultInjection() {
        return faultInjection;
    }

    public void setFaultInjection(boolean faultInjection) {
        this.faultInjection = faultInjection;
    }

    public static class Erp {
        /** Default goes THROUGH toxiproxy (proxy "erp" 18180 -> 18080). */
        private String baseUrl = "http://127.0.0.1:18180";
        private final Wss wss = new Wss();
        private int connectTimeoutMs = 500;
        private int receiveTimeoutMs = 2000;
        private final Retry retry = new Retry();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Wss getWss() {
            return wss;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReceiveTimeoutMs() {
            return receiveTimeoutMs;
        }

        public void setReceiveTimeoutMs(int receiveTimeoutMs) {
            this.receiveTimeoutMs = receiveTimeoutMs;
        }

        public Retry getRetry() {
            return retry;
        }

        public static class Wss {
            // Sim credentials are env-indirected in application.yml
            // (${ERP_WSS_USERNAME:esb-client} / ${ERP_WSS_PASSWORD:...}) — kept
            // literal-free here so source never carries credential values.
            private String username = "";
            private String password = "";

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }
        }

        public static class Retry {
            private int maxAttempts = 3;
            private long initialBackoffMs = 200;
            private double multiplier = 2.0;

            public int getMaxAttempts() {
                return maxAttempts;
            }

            public void setMaxAttempts(int maxAttempts) {
                this.maxAttempts = maxAttempts;
            }

            public long getInitialBackoffMs() {
                return initialBackoffMs;
            }

            public void setInitialBackoffMs(long initialBackoffMs) {
                this.initialBackoffMs = initialBackoffMs;
            }

            public double getMultiplier() {
                return multiplier;
            }

            public void setMultiplier(double multiplier) {
                this.multiplier = multiplier;
            }
        }
    }

    public static class Kafka {
        private String brokers = "127.0.0.1:39092";
        private String ordersTopic = "silkroute.orders.events";
        private String dlqTopic = "silkroute.esb.dlq";
        private long maxBlockMs = 3000;

        public String getBrokers() {
            return brokers;
        }

        public void setBrokers(String brokers) {
            this.brokers = brokers;
        }

        public String getOrdersTopic() {
            return ordersTopic;
        }

        public void setOrdersTopic(String ordersTopic) {
            this.ordersTopic = ordersTopic;
        }

        public String getDlqTopic() {
            return dlqTopic;
        }

        public void setDlqTopic(String dlqTopic) {
            this.dlqTopic = dlqTopic;
        }

        public long getMaxBlockMs() {
            return maxBlockMs;
        }

        public void setMaxBlockMs(long maxBlockMs) {
            this.maxBlockMs = maxBlockMs;
        }
    }

    public static class CircuitBreaker {
        private float failureRateThreshold = 50;
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 6;
        private long waitDurationInOpenStateMs = 2000;
        private int permittedNumberOfCallsInHalfOpenState = 3;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public long getWaitDurationInOpenStateMs() {
            return waitDurationInOpenStateMs;
        }

        public void setWaitDurationInOpenStateMs(long waitDurationInOpenStateMs) {
            this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }

    public static class Idempotency {
        private int ttlHours = 24;

        public int getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(int ttlHours) {
            this.ttlHours = ttlHours;
        }
    }
}

package com.mapleretail.silkroute.esb.idem;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.mapleretail.silkroute.esb.config.EsbProperties;

/**
 * Redis-backed idempotent consumer (SETNX + TTL):
 *   claim  → SET esb:idem:&lt;key&gt; INFLIGHT NX EX 24h
 *   done   → SET esb:idem:done:&lt;key&gt; &lt;final json&gt; EX 24h
 * Redis unavailability degrades to allow-through (logged): the ERP-side
 * duplicate guard (ORD-DUP-REF) remains as the safety net.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger LOG = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    public static final String CLAIM_PREFIX = "esb:idem:";
    public static final String DONE_PREFIX = "esb:idem:done:";
    private static final String CLAIM_VALUE = "INFLIGHT";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redis, EsbProperties properties) {
        this.redis = redis;
        this.ttl = Duration.ofHours(properties.getIdempotency().getTtlHours());
    }

    @Override
    public boolean claim(String key) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(CLAIM_PREFIX + key, CLAIM_VALUE, ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            LOG.warn("Redis unavailable for idempotency claim (allowing through): {}", e.getMessage());
            return true;
        }
    }

    @Override
    public Optional<String> getCompleted(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(DONE_PREFIX + key));
        } catch (Exception e) {
            LOG.warn("Redis unavailable for idempotency lookup: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void storeCompleted(String key, String finalResponseJson) {
        try {
            redis.opsForValue().set(DONE_PREFIX + key, finalResponseJson, ttl);
        } catch (Exception e) {
            LOG.warn("Redis unavailable for idempotency completion: {}", e.getMessage());
        }
    }

    @Override
    public void release(String key) {
        try {
            redis.delete(CLAIM_PREFIX + key);
        } catch (Exception e) {
            LOG.warn("Redis unavailable for idempotency release: {}", e.getMessage());
        }
    }
}

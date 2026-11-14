package com.azentio.doppel.screening;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stores {@link CachedMatch} as JSON with a TTL. Redis failures are treated as a cache miss (the request is
 * scored normally), so Redis being down slows screening down but never breaks it.
 */
@Component
@ConditionalOnProperty(prefix = "doppel", name = "store", havingValue = "redis", matchIfMissing = true)
public class RedisScreeningCache implements ScreeningCache {

    private static final Logger log = LoggerFactory.getLogger(RedisScreeningCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisScreeningCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CachedMatch> get(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, CachedMatch.class));
        } catch (Exception e) {
            log.warn("Screening cache read failed, scoring without cache: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, CachedMatch value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Screening cache write failed: {}", e.getMessage());
        }
    }
}

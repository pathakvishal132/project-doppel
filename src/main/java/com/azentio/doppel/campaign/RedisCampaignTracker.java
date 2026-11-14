package com.azentio.doppel.campaign;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Sorted set per registrant ({@code doppel:campaign:<registrantRef>}), updated by one Lua script so that
 * prune + add + count + expire is a single atomic Redis operation.
 */
@Component
@ConditionalOnProperty(prefix = "doppel", name = "store", havingValue = "redis", matchIfMissing = true)
public class RedisCampaignTracker implements CampaignTracker {

    static final String KEY_PREFIX = "doppel:campaign:";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;

    public RedisCampaignTracker(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/campaign_add_and_count.lua"));
        this.script.setResultType(Long.class);
    }

    @Override
    public int recordFlaggedVpa(String registrantRef, String vpa, Instant timestamp, Duration window) {
        Long count = redis.execute(script, List.of(KEY_PREFIX + registrantRef),
                String.valueOf(timestamp.toEpochMilli()), String.valueOf(window.toMillis()), vpa);
        return count == null ? 0 : count.intValue();
    }
}

package com.azentio.doppel.campaign;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Same rules as campaign_add_and_count.lua, in process (doppel.store=memory, unit tests). */
@Component
@ConditionalOnProperty(prefix = "doppel", name = "store", havingValue = "memory")
public class InMemoryCampaignTracker implements CampaignTracker {

    /** registrantRef -> (vpa -> latest timestamp in ms) */
    private final Map<String, Map<String, Long>> flagged = new HashMap<>();

    @Override
    public synchronized int recordFlaggedVpa(String registrantRef, String vpa, Instant timestamp, Duration window) {
        long ts = timestamp.toEpochMilli();
        long windowStart = ts - window.toMillis();
        Map<String, Long> vpas = flagged.computeIfAbsent(registrantRef, k -> new HashMap<>());
        vpas.values().removeIf(seen -> seen < windowStart);
        vpas.merge(vpa, ts, Math::max);
        return (int) vpas.values().stream().filter(seen -> seen >= windowStart).count();
    }

    public synchronized void clear() {
        flagged.clear();
    }
}

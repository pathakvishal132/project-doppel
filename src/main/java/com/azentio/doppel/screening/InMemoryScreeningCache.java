package com.azentio.doppel.screening;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** In-process screening cache with TTL, used by unit tests (doppel.store=memory). */
@Component
@ConditionalOnProperty(prefix = "doppel", name = "store", havingValue = "memory")
public class InMemoryScreeningCache implements ScreeningCache {

    private record Entry(CachedMatch value, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryScreeningCache(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<CachedMatch> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, CachedMatch value, Duration ttl) {
        entries.put(key, new Entry(value, clock.instant().plus(ttl)));
    }

    public void clear() {
        entries.clear();
    }
}

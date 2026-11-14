package com.azentio.doppel.screening;

import java.time.Duration;
import java.util.Optional;

/** Cache for brand-matching results. Redis in the application, in-memory in unit tests. */
public interface ScreeningCache {

    Optional<CachedMatch> get(String key);

    void put(String key, CachedMatch value, Duration ttl);
}

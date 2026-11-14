package com.azentio.doppel.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's built-in cache. With no cache provider on the classpath Spring Boot uses an
 * in-memory ConcurrentMapCacheManager (per application instance, no Redis).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String BRANDS_CACHE = "brands";
}

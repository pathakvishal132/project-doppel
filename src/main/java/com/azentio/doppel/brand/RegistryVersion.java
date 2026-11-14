package com.azentio.doppel.brand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Monotonic version of the brand registry, stored in PostgreSQL. It is bumped in the same transaction as every
 * registry change and is part of every cache key (brand list cache and Redis screening cache), so cached data
 * built from an older registry can never be served again.
 */
@Component
public class RegistryVersion {

    private final JdbcTemplate jdbc;

    public RegistryVersion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long current() {
        return jdbc.queryForObject("SELECT version FROM brand_registry_version WHERE id = 1", Long.class);
    }

    /** Must run inside the transaction that changes the registry. */
    void bump() {
        jdbc.update("UPDATE brand_registry_version SET version = version + 1 WHERE id = 1");
    }
}

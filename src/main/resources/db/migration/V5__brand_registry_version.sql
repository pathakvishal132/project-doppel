-- Bumped in the same transaction as every brand registry change. It is part of every cache key,
-- so a registry change makes all previously cached results unreachable.
CREATE TABLE brand_registry_version (
    id      INT    PRIMARY KEY,
    version BIGINT NOT NULL
);
INSERT INTO brand_registry_version (id, version) VALUES (1, 1);

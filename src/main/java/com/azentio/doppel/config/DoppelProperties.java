package com.azentio.doppel.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Scores, thresholds, lure words, cache TTL and HMAC secret from application.yml ("doppel.*"). */
@ConfigurationProperties(prefix = "doppel")
public record DoppelProperties(
        String hmacSecret,
        Scores scores,
        Thresholds thresholds,
        List<String> lureKeywords,
        Duration screeningCacheTtl) {

    public record Scores(int brandExact, int homoglyph, int typosquat, int lureKeyword) {
    }

    public record Thresholds(int block, int review) {
    }
}

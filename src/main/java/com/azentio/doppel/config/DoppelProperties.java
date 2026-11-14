package com.azentio.doppel.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Scores, thresholds, lure words, cache TTL, campaign window and HMAC secret from application.yml ("doppel.*"). */
@ConfigurationProperties(prefix = "doppel")
public record DoppelProperties(
        String hmacSecret,
        Scores scores,
        Thresholds thresholds,
        List<String> lureKeywords,
        Duration screeningCacheTtl,
        Campaign campaign) {

    public record Scores(int brandExact, int homoglyph, int typosquat, int lureKeyword, int campaignBurst) {
    }

    public record Thresholds(int block, int review) {
    }

    /** A registrant is flagged when it has at least minDistinctVpas flagged VPAs within the window. */
    public record Campaign(Duration window, int minDistinctVpas) {
    }
}

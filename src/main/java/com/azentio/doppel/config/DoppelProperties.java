package com.azentio.doppel.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Scores, thresholds, lure words and the HMAC secret, bound from application.yml ("doppel.*"). */
@ConfigurationProperties(prefix = "doppel")
public record DoppelProperties(
        String hmacSecret,
        Scores scores,
        Thresholds thresholds,
        List<String> lureKeywords) {

    public record Scores(int brandExact, int homoglyph, int typosquat, int lureKeyword) {
    }

    public record Thresholds(int block, int review) {
    }
}

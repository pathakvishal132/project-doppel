package com.azentio.doppel.screening;

import java.util.List;

import com.azentio.doppel.matching.MatchResult;

/**
 * The brand-matching part of a screening result: what is stored in the screening cache.
 * Decision, campaign check, persistence and alerting are NOT cached; they run on every request.
 */
public record CachedMatch(Long brandId, String brandName, int baseScore, List<String> reasons) {

    static CachedMatch from(MatchResult result) {
        return new CachedMatch(result.brandId(), result.brandName(), result.score(), List.copyOf(result.reasons()));
    }
}

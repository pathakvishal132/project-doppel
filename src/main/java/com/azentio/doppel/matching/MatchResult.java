package com.azentio.doppel.matching;

import java.util.List;

/** Outcome of screening one payee. brandId/brandName are null when no brand matched. */
public record MatchResult(Long brandId, String brandName, int score, Decision decision, List<String> reasons) {
}

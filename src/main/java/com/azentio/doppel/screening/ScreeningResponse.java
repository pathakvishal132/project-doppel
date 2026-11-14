package com.azentio.doppel.screening;

import java.time.Instant;
import java.util.List;

import com.azentio.doppel.matching.Decision;

public record ScreeningResponse(
        String requestId,
        String vpa,
        Decision decision,
        int score,
        Long matchedBrandId,
        String matchedBrandName,
        List<String> reasons,
        String maskedAccount,
        Instant screenedAt) {

    static ScreeningResponse from(ScreeningRecord r) {
        return new ScreeningResponse(r.getRequestId(), r.getVpa(), r.getDecision(), r.getScore(),
                r.getMatchedBrandId(), r.getMatchedBrandName(), Reasons.split(r.getReasons()),
                r.getMaskedAccount(), r.getCreatedAt());
    }
}

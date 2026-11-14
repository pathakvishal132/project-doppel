package com.azentio.doppel.alert;

import java.time.Instant;
import java.util.List;

import com.azentio.doppel.matching.Decision;
import com.azentio.doppel.screening.Reasons;

/** Exposes the masked account only; the full account number is never stored. */
public record AlertResponse(
        Long id,
        String requestId,
        String vpa,
        String displayName,
        Long brandId,
        String brandName,
        Decision decision,
        int score,
        List<String> reasons,
        String maskedAccount,
        String registrantRef,
        AlertStatus status,
        Disposition disposition,
        Instant createdAt,
        Instant updatedAt) {

    static AlertResponse from(Alert a) {
        return new AlertResponse(a.getId(), a.getRequestId(), a.getVpa(), a.getDisplayName(), a.getBrandId(),
                a.getBrandName(), a.getDecision(), a.getScore(), Reasons.split(a.getReasons()),
                a.getMaskedAccount(), a.getRegistrantRef(), a.getStatus(), a.getDisposition(), a.getCreatedAt(),
                a.getUpdatedAt());
    }
}

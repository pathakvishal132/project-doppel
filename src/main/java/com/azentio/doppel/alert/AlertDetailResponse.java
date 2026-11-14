package com.azentio.doppel.alert;

import java.time.Instant;
import java.util.List;

/** Alert plus per-signal scores and details of the originating screening. */
public record AlertDetailResponse(
        AlertResponse alert,
        List<SignalResponse> signals,
        Instant requestTimestamp,
        Instant screenedAt) {
}

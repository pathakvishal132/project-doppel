package com.azentio.doppel.alert;

public record AlertSummaryResponse(long total, long open, long closed, long review, long block,
        long confirmedSuspicious, long legitimate) {
}

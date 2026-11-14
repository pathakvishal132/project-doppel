package com.azentio.doppel.screening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ScreeningRequestTest {

    @Test
    void toStringRedactsRegistrantAccount() {
        var request = new ScreeningRequest("req-1", "ab@ybl", "Name", "123456789012", Instant.EPOCH);
        assertThat(request.toString()).doesNotContain("123456789012").contains("registrantAccount=***");
    }
}

package com.azentio.doppel.screening;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ScreeningRequest(
        @NotBlank(message = "is required")
        @Size(max = 64, message = "must be at most 64 characters")
        String requestId,

        @NotNull(message = "is required")
        @Pattern(regexp = VPA_REGEX, message = "must be a valid VPA (e.g. name@psp)")
        String vpa,

        @NotNull(message = "is required")
        @Size(min = 1, max = 100, message = "must be 1-100 characters")
        String displayName,

        @NotNull(message = "is required")
        @Pattern(regexp = "^[0-9]{9,18}$", message = "must be 9-18 digits")
        String registrantAccount,

        @NotNull(message = "is required")
        Instant timestamp) {

    /** UPI VPA format: handle of 2-256 chars [a-zA-Z0-9.-_], '@', PSP of 2-64 letters. */
    public static final String VPA_REGEX = "^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$";

    /** Never print the registrant account number. */
    @Override
    public String toString() {
        return "ScreeningRequest[requestId=" + requestId + ", vpa=" + vpa + ", displayName=" + displayName
                + ", registrantAccount=***, timestamp=" + timestamp + "]";
    }
}

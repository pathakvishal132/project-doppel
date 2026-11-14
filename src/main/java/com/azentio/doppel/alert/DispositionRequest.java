package com.azentio.doppel.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DispositionRequest(
        @NotNull(message = "is required")
        Disposition disposition,

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String analyst,

        @Size(max = 1000, message = "must be at most 1000 characters")
        String comment) {
}

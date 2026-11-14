package com.azentio.doppel.brand;

import java.util.List;

import com.azentio.doppel.screening.ScreeningRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBrandRequest(
        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotEmpty(message = "must contain at least one token")
        @Size(max = 20, message = "must contain at most 20 tokens")
        List<@NotBlank(message = "must not be blank")
             @Size(max = 64, message = "must be at most 64 characters") String> tokens,

        @NotNull(message = "is required")
        @Size(max = 20, message = "must contain at most 20 VPAs")
        List<@NotNull(message = "must not be null")
             @Pattern(regexp = ScreeningRequest.VPA_REGEX, message = "must be a valid VPA (e.g. name@psp)")
             String> officialVpas) {
}

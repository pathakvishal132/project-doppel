package com.azentio.doppel.brand;

import java.util.List;

public record BrandResponse(Long id, String name, List<String> matchingTokens, List<String> officialVpas) {

    public static BrandResponse from(Brand brand) {
        return new BrandResponse(
                brand.getId(),
                brand.getName(),
                brand.getTokens().stream().sorted().toList(),
                brand.getOfficialVpas().stream().sorted().toList());
    }
}

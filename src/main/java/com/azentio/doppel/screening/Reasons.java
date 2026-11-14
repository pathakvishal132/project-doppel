package com.azentio.doppel.screening;

import java.util.Arrays;
import java.util.List;

/** Reasons are stored as a single ";"-separated text column. */
public final class Reasons {

    private static final String SEPARATOR = ";";

    private Reasons() {
    }

    public static String join(List<String> reasons) {
        return String.join(SEPARATOR, reasons);
    }

    public static List<String> split(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return List.of();
        }
        return Arrays.asList(reasons.split(SEPARATOR));
    }
}

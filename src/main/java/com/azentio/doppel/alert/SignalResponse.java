package com.azentio.doppel.alert;

/** One matched signal, e.g. type=HOMOGLYPH detail=shopkart score=60. */
public record SignalResponse(String type, String detail, int score) {
}

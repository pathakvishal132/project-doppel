package com.azentio.doppel.alert;

/** Analyst verdict on an alert. */
public enum Disposition {
    /** Payee is genuine – false positive. */
    LEGITIMATE,
    /** Analyst confirms brand impersonation. */
    CONFIRMED_SUSPICIOUS
}

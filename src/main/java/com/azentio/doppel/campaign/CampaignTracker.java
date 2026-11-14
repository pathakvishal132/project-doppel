package com.azentio.doppel.campaign;

import java.time.Duration;
import java.time.Instant;

/** Tracks distinct flagged VPAs per registrant inside a sliding time window. */
public interface CampaignTracker {

    /**
     * Records a flagged VPA for the registrant at the request timestamp and returns the number of distinct flagged
     * VPAs for that registrant whose latest timestamp is at or after {@code timestamp - window}, including this one.
     * Must be atomic: concurrent calls for one registrant see each other's additions.
     */
    int recordFlaggedVpa(String registrantRef, String vpa, Instant timestamp, Duration window);
}

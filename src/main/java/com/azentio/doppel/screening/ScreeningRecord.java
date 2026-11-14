package com.azentio.doppel.screening;

import java.time.Instant;

import com.azentio.doppel.matching.Decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row per screening request. Holds only the masked account and its HMAC, never the full number. */
@Entity
@Table(name = "screening")
public class ScreeningRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private String requestId;

    @Column(nullable = false)
    private String vpa;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "masked_account", nullable = false)
    private String maskedAccount;

    @Column(name = "registrant_ref", nullable = false)
    private String registrantRef;

    @Column(name = "matched_brand_id")
    private Long matchedBrandId;

    @Column(name = "matched_brand_name")
    private String matchedBrandName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private String reasons;

    @Column(name = "request_timestamp", nullable = false)
    private Instant requestTimestamp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean cached;

    protected ScreeningRecord() {
    }

    public ScreeningRecord(String requestId, String vpa, String displayName, String maskedAccount,
            String registrantRef, Long matchedBrandId, String matchedBrandName, Decision decision, int score,
            String reasons, Instant requestTimestamp, Instant createdAt, boolean cached) {
        this.requestId = requestId;
        this.vpa = vpa;
        this.displayName = displayName;
        this.maskedAccount = maskedAccount;
        this.registrantRef = registrantRef;
        this.matchedBrandId = matchedBrandId;
        this.matchedBrandName = matchedBrandName;
        this.decision = decision;
        this.score = score;
        this.reasons = reasons;
        this.requestTimestamp = requestTimestamp;
        this.createdAt = createdAt;
        this.cached = cached;
    }

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getVpa() { return vpa; }
    public String getDisplayName() { return displayName; }
    public String getMaskedAccount() { return maskedAccount; }
    public String getRegistrantRef() { return registrantRef; }
    public Long getMatchedBrandId() { return matchedBrandId; }
    public String getMatchedBrandName() { return matchedBrandName; }
    public Decision getDecision() { return decision; }
    public int getScore() { return score; }
    public String getReasons() { return reasons; }
    public Instant getRequestTimestamp() { return requestTimestamp; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isCached() { return cached; }
}

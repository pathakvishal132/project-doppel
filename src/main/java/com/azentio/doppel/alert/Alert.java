package com.azentio.doppel.alert;

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

/** Created only for REVIEW and BLOCK screenings. */
@Entity
@Table(name = "alert")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private String requestId;

    @Column(nullable = false)
    private String vpa;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "brand_id")
    private Long brandId;

    @Column(name = "brand_name")
    private String brandName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private String reasons;

    @Column(name = "masked_account", nullable = false)
    private String maskedAccount;

    @Column(name = "registrant_ref", nullable = false)
    private String registrantRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Alert() {
    }

    public Alert(String requestId, String vpa, String displayName, Long brandId, String brandName,
            Decision decision, int score, String reasons, String maskedAccount, String registrantRef,
            AlertStatus status, Instant createdAt) {
        this.requestId = requestId;
        this.vpa = vpa;
        this.displayName = displayName;
        this.brandId = brandId;
        this.brandName = brandName;
        this.decision = decision;
        this.score = score;
        this.reasons = reasons;
        this.maskedAccount = maskedAccount;
        this.registrantRef = registrantRef;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getVpa() { return vpa; }
    public String getDisplayName() { return displayName; }
    public Long getBrandId() { return brandId; }
    public String getBrandName() { return brandName; }
    public Decision getDecision() { return decision; }
    public int getScore() { return score; }
    public String getReasons() { return reasons; }
    public String getMaskedAccount() { return maskedAccount; }
    public String getRegistrantRef() { return registrantRef; }
    public AlertStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}

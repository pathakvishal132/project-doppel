package com.azentio.doppel.alert;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only history of everything that happened to an alert. */
@Entity
@Table(name = "alert_audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private AlertStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false)
    private AlertStatus newStatus;

    @Column(nullable = false)
    private String analyst;

    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(Long alertId, AuditAction action, AlertStatus previousStatus, AlertStatus newStatus,
            String analyst, String comment, Instant createdAt) {
        this.alertId = alertId;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.analyst = analyst;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getAlertId() { return alertId; }
    public AuditAction getAction() { return action; }
    public AlertStatus getPreviousStatus() { return previousStatus; }
    public AlertStatus getNewStatus() { return newStatus; }
    public String getAnalyst() { return analyst; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.azentio.doppel.alert;

import java.time.Instant;

public record AuditEventResponse(Long id, Long alertId, AuditAction action, AlertStatus previousStatus,
        AlertStatus newStatus, String analyst, String comment, Instant createdAt) {

    static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getAlertId(), e.getAction(), e.getPreviousStatus(),
                e.getNewStatus(), e.getAnalyst(), e.getComment(), e.getCreatedAt());
    }
}

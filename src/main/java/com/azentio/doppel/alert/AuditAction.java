package com.azentio.doppel.alert;

public enum AuditAction {
    ALERT_CREATED,
    MARKED_LEGITIMATE,
    CONFIRMED_SUSPICIOUS;

    static AuditAction of(Disposition disposition) {
        return disposition == Disposition.LEGITIMATE ? MARKED_LEGITIMATE : CONFIRMED_SUSPICIOUS;
    }
}

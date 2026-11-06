package com.team404.corrosion.alert.lifecycle;

/**
 * OPEN → ACKNOWLEDGED → RESOLVED. A technician may also resolve an OPEN alert directly, and the signal consumer
 * resolves an alert of either active status after its quiet period. RESOLVED is final: a condition that returns
 * opens a new alert.
 */
public enum AlertStatus {
    OPEN, ACKNOWLEDGED, RESOLVED;

    public boolean canAcknowledge() {
        return this == OPEN;
    }

    public boolean canResolve() {
        return this != RESOLVED;
    }

    public boolean canLogAction() {
        return this != RESOLVED;
    }
}

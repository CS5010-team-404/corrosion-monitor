package com.team404.corrosion.alert.lifecycle;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * OPEN → ACKNOWLEDGED → RESOLVED. A technician may also resolve an OPEN alert directly, and the signal consumer
 * resolves an alert of either active status after its quiet period. RESOLVED is final: a condition that returns
 * opens a new alert. AlertRepository builds its WHERE guards from these rules ({@link #sqlIn}).
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

    /** Only an alert nobody has acknowledged escalates. */
    public boolean canEscalate() {
        return this == OPEN;
    }

    /** {@code ('OPEN','ACKNOWLEDGED')} for the statuses the rule allows; constant text, safe to put in SQL. */
    static String sqlIn(Predicate<AlertStatus> rule) {
        return Arrays.stream(values()).filter(rule).map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(",", "(", ")"));
    }
}

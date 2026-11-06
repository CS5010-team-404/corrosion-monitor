package com.team404.corrosion.alert.signal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** What the signal rules need from the alert table. Implemented with JDBC at the edge and in memory in tests. */
public interface AlertStore {

    /** The alert for this key that is not RESOLVED, if any. There is at most one. */
    Optional<StoredAlert> findUnresolved(String dedupKey);

    /** Returns the new alert's id. */
    long open(Signal signal, Instant openedAt);

    /** Takes the signal's latest severity, text and sensor, and drops conditionClearedAt (the condition is back). */
    void refresh(long alertId, Signal signal);

    void markConditionCleared(long alertId, Instant clearedAt);

    /** Resolves every unresolved alert whose condition cleared at or before the cutoff. Returns the ids it resolved. */
    List<Long> resolveQuiet(Instant clearedBefore, Instant resolvedAt);

    record StoredAlert(long id, Instant conditionClearedAt) {
    }
}

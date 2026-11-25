package com.team404.corrosion.analytics.signal;

import java.time.Instant;
import java.util.Map;

/** Analytics' side of the shared table alert_signals (docs/CONTRACTS.md): what it wrote last, and writing a row. */
public interface SignalStore {

    /** The state (RAISED or CLEARED) and severity of the latest ANALYTICS row of one dedup key. */
    record LastSignal(String state, String severity) {
    }

    /**
     * A row to write. {@code payload} goes in as JSON; the alert service reads {@code deviceId} from it.
     */
    record NewSignal(String type, String severity, String state, long equipmentId, Long sensorId, String dedupKey,
                     String title, String message, Map<String, Object> payload, Instant createdAt) {
    }

    /** The latest ANALYTICS signal of every dedup key that has one. */
    Map<String, LastSignal> lastSignals();

    void append(NewSignal signal);
}

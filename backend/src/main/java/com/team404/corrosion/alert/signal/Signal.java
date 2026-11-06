package com.team404.corrosion.alert.signal;

import java.time.Instant;

/** One unprocessed row of alert_signals (docs/CONTRACTS.md). */
public record Signal(
        long id,
        String source,
        String type,
        String severity,
        String state,
        Long equipmentId,
        Long sensorId,
        String deviceId,
        String dedupKey,
        String title,
        String message,
        Instant createdAt) {

    public static final String RAISED = "RAISED";
    public static final String CLEARED = "CLEARED";
}

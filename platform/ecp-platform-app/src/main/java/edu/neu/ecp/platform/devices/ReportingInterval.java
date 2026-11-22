package edu.neu.ecp.platform.devices;

import java.util.Optional;

/**
 * How often a device may be configured to report (FR1). Five seconds keeps the ingestion load sane; an hour is the
 * longest gap that still lets sensor health notice a dead device the same day. Mirrored in the UI
 * (frontend/src/lib/sensors.ts).
 */
public final class ReportingInterval {

    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 3600;

    private ReportingInterval() {
    }

    /** Why {@code seconds} is not an acceptable interval, or empty if it is. */
    public static Optional<String> problem(Integer seconds) {
        if (seconds == null) {
            return Optional.of("expectedIntervalS is required");
        }
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            return Optional.of("expectedIntervalS must be between " + MIN_SECONDS + " and " + MAX_SECONDS + " seconds");
        }
        return Optional.empty();
    }
}

package edu.neu.ecp.health.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Raw result of the rules for one evaluation, before hysteresis.
 *
 * @param detected issues whose condition is true right now
 * @param evidence numbers that explain each detected issue (shown in the UI and stored with events)
 * @param metrics  window statistics, stored on the sensor_health row
 */
public record Evaluation(long sensorId, Connectivity connectivity, Set<HealthIssue> detected,
                         Map<HealthIssue, Map<String, Object>> evidence, WindowMetrics metrics, Instant evaluatedAt) {

    public Evaluation {
        detected = Set.copyOf(detected);
        evidence = Map.copyOf(evidence);
    }

    /** Statistics about the recent window. {@code missingPct} is null when there was too little data to judge. */
    public record WindowMetrics(int readings, Integer expectedReadings, Double missingPct, int spikes,
                                int outOfRange, Long medianSkewSeconds, Instant lastSeenAt, Long lastSeqNo,
                                Instant lastMeasuredAt) {
    }
}

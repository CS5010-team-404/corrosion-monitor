package edu.neu.ecp.health.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Raw result of the rules for one evaluation, before hysteresis.
 *
 * @param detected  issues whose condition is true right now
 * @param evidence  numbers that explain each detected issue (shown in the UI and stored with events)
 * @param metrics   window statistics, stored on the sensor_health row
 * @param undecided issues the rules could not judge this time (OFFLINE during the startup grace): an open episode
 *                  stays open and a closed one stays closed
 */
public record Evaluation(long sensorId, Connectivity connectivity, Set<HealthIssue> detected,
                         Map<HealthIssue, Map<String, Object>> evidence, WindowMetrics metrics, Instant evaluatedAt,
                         Set<HealthIssue> undecided) {

    public Evaluation {
        detected = Set.copyOf(detected);
        evidence = Map.copyOf(evidence);
        undecided = undecided == null ? Set.of() : Set.copyOf(undecided);
    }

    public Evaluation(long sensorId, Connectivity connectivity, Set<HealthIssue> detected,
                      Map<HealthIssue, Map<String, Object>> evidence, WindowMetrics metrics, Instant evaluatedAt) {
        this(sensorId, connectivity, detected, evidence, metrics, evaluatedAt, Set.of());
    }

    /** Statistics about the recent window. {@code missingPct} is null when there was too little data to judge. */
    public record WindowMetrics(int readings, Integer expectedReadings, Double missingPct, int spikes,
                                int outOfRange, Long medianSkewSeconds, Instant lastSeenAt, Long lastSeqNo,
                                Instant lastMeasuredAt) {
    }
}

package edu.neu.ecp.health.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Everything known about one sensor at evaluation time.
 *
 * @param window             accepted readings received within the policy window (any order; sorted here)
 * @param lastSeenAt         received time of the newest accepted reading ever, or null if never seen
 * @param signatureFailures  rejected-signature requests for this device within the window
 * @param now                evaluation time
 */
public record Observation(SensorRef sensor, List<ReadingSample> window, Instant lastSeenAt, int signatureFailures,
                          Instant now) {

    public Observation {
        Objects.requireNonNull(sensor, "sensor");
        Objects.requireNonNull(now, "now");
        window = window == null ? List.of()
                : window.stream().sorted(Comparator.comparing(ReadingSample::receivedAt)
                        .thenComparingLong(ReadingSample::seqNo)).toList();
    }
}

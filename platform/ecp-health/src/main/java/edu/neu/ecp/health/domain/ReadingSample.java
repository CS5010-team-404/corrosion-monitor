package edu.neu.ecp.health.domain;

import java.time.Instant;

/** The slice of a stored reading that health rules need. */
public record ReadingSample(long seqNo, Instant measuredAt, Instant receivedAt, double temperatureC, double humidityPct) {
}

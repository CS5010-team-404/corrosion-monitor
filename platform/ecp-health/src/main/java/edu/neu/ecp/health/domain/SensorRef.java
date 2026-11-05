package edu.neu.ecp.health.domain;

import java.time.Duration;
import java.time.Instant;

/** What the health rules need to know about a registered sensor. */
public record SensorRef(long sensorId, String deviceId, Long equipmentId, Duration expectedInterval, Instant createdAt) {
}

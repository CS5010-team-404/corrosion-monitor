package edu.neu.ecp.common.signal;

import java.util.Map;
import java.util.Objects;

/**
 * One row in {@code alert_signals}: the only way analytics and sensor health talk to the alert service.
 *
 * @param dedupKey stable key for "the same condition", e.g. {@code HEALTH:SENSOR_OFFLINE:12} or
 *                 {@code ANALYTICS:CONDENSATION_RISK:4}. A CLEARED signal must reuse the RAISED key.
 */
public record AlertSignal(
        SignalSource source,
        String type,
        Severity severity,
        SignalState state,
        Long equipmentId,
        Long sensorId,
        String dedupKey,
        String title,
        String message,
        Map<String, Object> payload) {

    public AlertSignal {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(dedupKey, "dedupKey");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}

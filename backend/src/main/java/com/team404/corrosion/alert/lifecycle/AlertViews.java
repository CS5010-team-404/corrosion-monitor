package com.team404.corrosion.alert.lifecycle;

import java.time.Instant;
import java.util.List;

/** JSON shapes of frontend/src/api/types.ts. */
public final class AlertViews {

    private AlertViews() {
    }

    public record AlertView(long id, String type, String source, String severity, AlertStatus status,
                            int escalationLevel, Long equipmentId, String equipmentName, String locationName,
                            Long sensorId, String deviceId, String title, String message, Instant openedAt,
                            Instant acknowledgedAt, String acknowledgedBy, Instant resolvedAt, String resolvedBy,
                            Instant conditionClearedAt) {
    }

    public record ActionView(long id, String actionType, String notes, String username, Instant performedAt) {
    }

    public record AlertDetail(AlertView alert, List<ActionView> actions) {
    }
}

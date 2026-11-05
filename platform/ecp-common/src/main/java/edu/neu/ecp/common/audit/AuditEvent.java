package edu.neu.ecp.common.audit;

import java.util.Map;
import java.util.Objects;

/**
 * Something worth an audit entry. Every service records these through {@link AuditRecorder};
 * the audit service (Anela) chains them into the tamper-evident {@code audit_log}.
 *
 * @param action UPPER_SNAKE verb, e.g. ALERT_ACKNOWLEDGED, USER_LOGIN_FAILED, SENSOR_ISSUE_OPENED
 */
public record AuditEvent(
        ActorType actorType,
        String actor,
        String action,
        String entityType,
        String entityId,
        Map<String, Object> details) {

    public AuditEvent {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditEvent system(String service, String action, String entityType, Object entityId,
                                    Map<String, Object> details) {
        return new AuditEvent(ActorType.SYSTEM, service, action, entityType, String.valueOf(entityId), details);
    }

    public static AuditEvent user(String username, String action, String entityType, Object entityId,
                                  Map<String, Object> details) {
        return new AuditEvent(ActorType.USER, username, action, entityType, String.valueOf(entityId), details);
    }
}

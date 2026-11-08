package com.team404.corrosion.audit;

import java.time.LocalDateTime;

/**
 * One row of audit_outbox, as written by the other services (see LoginAudit and docs/CONTRACTS.md).
 * Times are UTC wall-clock values read straight from the DATETIME column.
 */
public record OutboxEvent(
        long id,
        LocalDateTime occurredAt,
        String actorType,
        String actor,
        String action,
        String entityType,
        String entityId,
        String details) {
}

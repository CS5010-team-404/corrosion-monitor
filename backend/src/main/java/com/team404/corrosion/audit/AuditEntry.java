package com.team404.corrosion.audit;

import java.time.LocalDateTime;

/**
 * One row of audit_log: an outbox event plus the chain fields. Every field except entryHash goes into the hash.
 * Times are UTC with millisecond precision, like the DATETIME(3) columns they are stored in.
 */
public record AuditEntry(
        long id,
        long outboxId,
        LocalDateTime occurredAt,
        LocalDateTime recordedAt,
        String actorType,
        String actor,
        String action,
        String entityType,
        String entityId,
        String details,
        String prevHash,
        String entryHash) {

    public AuditEntry withEntryHash(String hash) {
        return new AuditEntry(id, outboxId, occurredAt, recordedAt, actorType, actor, action, entityType,
                entityId, details, prevHash, hash);
    }
}

package com.team404.corrosion.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The shapes the dashboard expects (docs/GAPS.md, frontend/src/api/types.ts) and the admin check, kept apart from the
 * controller so they can be tested without Spring.
 */
public final class AuditViews {

    private static final String ADMIN_ROLE = "ADMIN";

    /**
     * One audit entry as the UI reads it. Times are ISO-8601 instants in UTC. prevHash and hash are audit_log's
     * prev_hash and entry_hash, so anyone can follow the chain from the page.
     */
    public record EntryView(long id, Instant occurredAt, String actorType, String actor, String action,
                            String entityType, String entityId, String details, String prevHash, String hash) {
    }

    /** One page of GET /api/v1/audit: {items, page, size, total}, newest first; page starts at 0. */
    public record PageView(List<EntryView> items, int page, int size, long total) {
    }

    /** Result of the verify button: intact, how many entries were checked, and the first broken entry if any. */
    public record VerifyView(boolean intact, long checked, Long firstBrokenId, String message) {
    }

    private AuditViews() {
    }

    public static EntryView entry(AuditEntry e) {
        return new EntryView(e.id(), e.occurredAt().toInstant(ZoneOffset.UTC), e.actorType(), e.actor(), e.action(),
                e.entityType(), e.entityId(), e.details(), e.prevHash(), e.entryHash());
    }

    public static PageView page(AuditService.PageResult<AuditEntry> result) {
        return new PageView(result.items().stream().map(AuditViews::entry).toList(), result.page(), result.size(),
                result.total());
    }

    public static VerifyView verify(VerificationResult r) {
        return new VerifyView(r.valid(), r.entriesChecked(), r.firstBrokenId(), r.message());
    }

    /** True only when the token's roles claim contains exactly ADMIN. */
    public static boolean isAdmin(List<String> roles) {
        return roles != null && roles.contains(ADMIN_ROLE);
    }

    /**
     * True when the roles claim contains exactly MANAGER or ADMIN: may read the audit list and verify the chain
     * (docs/CONTRACTS.md).
     */
    public static boolean canViewLog(List<String> roles) {
        return roles != null && (roles.contains(ADMIN_ROLE) || roles.contains("MANAGER"));
    }
}

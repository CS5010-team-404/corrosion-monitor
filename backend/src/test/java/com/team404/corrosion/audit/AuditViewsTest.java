package com.team404.corrosion.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditViewsTest {

    @Test
    void entryUsesTheFieldsTheDashboardExpects() {
        AuditEntry entry = TestChains.entry(1, HashChain.GENESIS_HASH);

        AuditViews.EntryView view = AuditViews.entry(entry);

        assertEquals(1L, view.id());
        assertEquals(entry.occurredAt().toInstant(ZoneOffset.UTC), view.occurredAt());
        assertEquals("USER", view.actorType());
        assertEquals("user1@corrosionwatch.app", view.actor());
        assertEquals("USER_LOGIN", view.action());
        assertEquals("user", view.entityType());
        assertEquals("1", view.entityId());
        assertEquals("{\"role\":\"ADMIN\"}", view.details());
    }

    @Test
    void entryKeepsMissingOptionalFieldsAsNull() {
        AuditEntry base = TestChains.entry(1, HashChain.GENESIS_HASH);
        AuditEntry noEntity = new AuditEntry(base.id(), base.outboxId(), base.occurredAt(), base.recordedAt(),
                base.actorType(), base.actor(), base.action(), null, null, null, base.prevHash(), base.entryHash());

        AuditViews.EntryView view = AuditViews.entry(noEntity);

        assertNull(view.entityType());
        assertNull(view.entityId());
        assertNull(view.details());
    }

    @Test
    void intactChainMapsToIntactTrue() {
        AuditViews.VerifyView view = AuditViews.verify(VerificationResult.ok(5));

        assertTrue(view.intact());
        assertEquals(5L, view.checked());
        assertNull(view.firstBrokenId());
    }

    @Test
    void brokenChainMapsToIntactFalseWithTheFirstBrokenEntry() {
        AuditViews.VerifyView view = AuditViews.verify(VerificationResult.broken(2, 3, "row was modified"));

        assertFalse(view.intact());
        assertEquals(2L, view.checked());
        assertEquals(Long.valueOf(3), view.firstBrokenId());
        assertEquals("row was modified", view.message());
    }

    @Test
    void onlyAnAdminRoleIsAdmin() {
        assertTrue(AuditViews.isAdmin(List.of("ADMIN")));
        assertTrue(AuditViews.isAdmin(List.of("MANAGER", "ADMIN")));
        assertFalse(AuditViews.isAdmin(List.of("OPERATOR")));
        assertFalse(AuditViews.isAdmin(List.of("TECHNICIAN")));
        assertFalse(AuditViews.isAdmin(List.of("admin")));
        assertFalse(AuditViews.isAdmin(List.of()));
        assertFalse(AuditViews.isAdmin(null));
        assertTrue(AuditViews.canViewLog(List.of("MANAGER")));
        assertTrue(AuditViews.canViewLog(List.of("ADMIN")));
        assertFalse(AuditViews.canViewLog(List.of("OPERATOR")));
        assertFalse(AuditViews.canViewLog(List.of("TECHNICIAN")));
        assertFalse(AuditViews.canViewLog(null));
    }
}

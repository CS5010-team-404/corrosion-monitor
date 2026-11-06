package com.team404.corrosion.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashChainTest {

    private final AuditEntry base = TestChains.entry(2, HashChain.GENESIS_HASH);

    @Test
    void hashIsDeterministic() {
        assertEquals(HashChain.computeHash(base), HashChain.computeHash(base));
    }

    @Test
    void hashIs64LowerCaseHexCharacters() {
        String hash = HashChain.computeHash(base);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void genesisHashIs64Zeros() {
        assertEquals("0".repeat(64), HashChain.GENESIS_HASH);
    }

    @Test
    void changingAnySingleFieldChangesTheHash() {
        AuditEntry b = base;
        List<AuditEntry> variants = List.of(
                new AuditEntry(3, b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(), b.action(),
                        b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), 999, b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(), b.action(),
                        b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt().plusNanos(1_000_000), b.recordedAt(), b.actorType(),
                        b.actor(), b.action(), b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt().plusNanos(1_000_000), b.actorType(),
                        b.actor(), b.action(), b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), "DEVICE", b.actor(), b.action(),
                        b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), "someone-else",
                        b.action(), b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(),
                        "USER_LOGIN_FAILED", b.entityType(), b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(),
                        b.action(), "alert", b.entityId(), b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(),
                        b.action(), b.entityType(), "77", b.details(), b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(),
                        b.action(), b.entityType(), b.entityId(), "{\"role\":\"OPERATOR\"}", b.prevHash(), null),
                new AuditEntry(b.id(), b.outboxId(), b.occurredAt(), b.recordedAt(), b.actorType(), b.actor(),
                        b.action(), b.entityType(), b.entityId(), b.details(), "1".repeat(64), null));

        String original = HashChain.computeHash(b);
        for (AuditEntry variant : variants) {
            assertNotEquals(original, HashChain.computeHash(variant));
        }
    }

    @Test
    void entryHashFieldItselfIsNotPartOfTheHash() {
        AuditEntry withJunk = base.withEntryHash("not-a-real-hash");
        assertEquals(HashChain.computeHash(base), HashChain.computeHash(withJunk));
    }

    @Test
    void nullAndEmptyStringGiveDifferentHashes() {
        AuditEntry withNull = new AuditEntry(1, 1, base.occurredAt(), base.recordedAt(), "USER", null, "A", null,
                null, null, HashChain.GENESIS_HASH, null);
        AuditEntry withEmpty = new AuditEntry(1, 1, base.occurredAt(), base.recordedAt(), "USER", "", "A", "", "",
                "", HashChain.GENESIS_HASH, null);
        assertNotEquals(HashChain.computeHash(withNull), HashChain.computeHash(withEmpty));
    }

    @Test
    void movingTextAcrossAFieldBoundaryChangesTheHash() {
        AuditEntry first = new AuditEntry(1, 1, base.occurredAt(), base.recordedAt(), "USER", "a", "bc", null, null,
                null, HashChain.GENESIS_HASH, null);
        AuditEntry second = new AuditEntry(1, 1, base.occurredAt(), base.recordedAt(), "USER", "ab", "c", null, null,
                null, HashChain.GENESIS_HASH, null);
        assertNotEquals(HashChain.computeHash(first), HashChain.computeHash(second));
    }

    @Test
    void separatorCharactersInsideValuesCannotForgeAnotherEntry() {
        AuditEntry honest = new AuditEntry(1, 1, base.occurredAt(), base.recordedAt(), "USER", "a;1:b", "x", null,
                null, null, HashChain.GENESIS_HASH, null);
        AuditEntry forged = new AuditEntry(1, 1, base.occurredAt(), base.recordedAt(), "USER", "a", "1:bx", null,
                null, null, HashChain.GENESIS_HASH, null);
        assertNotEquals(HashChain.canonical(honest), HashChain.canonical(forged));
    }

    @Test
    void timesAlwaysUseThreeFractionalDigits() {
        AuditEntry wholeSecond = new AuditEntry(1, 1, LocalDateTime.of(2026, 11, 2, 10, 0, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0, 0), "USER", "a", "A", null, null, null,
                HashChain.GENESIS_HASH, null);
        assertTrue(HashChain.canonical(wholeSecond).contains("2026-11-02T10:00:00.000;"));
    }

    @Test
    void canonicalTextStartsWithAFormatVersion() {
        assertTrue(HashChain.canonical(base).startsWith("v1;"));
        assertFalse(HashChain.canonical(base).contains("null"));
    }
}

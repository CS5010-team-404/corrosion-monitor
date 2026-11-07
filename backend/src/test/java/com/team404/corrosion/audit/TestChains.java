package com.team404.corrosion.audit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Builds valid chains in memory, the same way AuditChainWorker does. */
final class TestChains {

    private TestChains() {
    }

    static AuditEntry entry(long id, String prevHash) {
        AuditEntry unsigned = new AuditEntry(
                id,
                id + 100,
                LocalDateTime.of(2026, 11, 2, 10, 0, (int) id, 123000000),
                LocalDateTime.of(2026, 11, 2, 10, 5, (int) id, 654000000),
                "USER",
                "user" + id + "@corrosionwatch.app",
                "USER_LOGIN",
                "user",
                String.valueOf(id),
                "{\"role\":\"ADMIN\"}",
                prevHash,
                null);
        return unsigned.withEntryHash(HashChain.computeHash(unsigned));
    }

    static List<AuditEntry> chain(int size) {
        List<AuditEntry> chain = new ArrayList<>();
        String prev = HashChain.GENESIS_HASH;
        for (int id = 1; id <= size; id++) {
            AuditEntry e = entry(id, prev);
            chain.add(e);
            prev = e.entryHash();
        }
        return chain;
    }

    /** Same entry with a different actor and the OLD hash, as an attacker editing one column would leave it. */
    static AuditEntry withActor(AuditEntry e, String actor) {
        return new AuditEntry(e.id(), e.outboxId(), e.occurredAt(), e.recordedAt(), e.actorType(), actor,
                e.action(), e.entityType(), e.entityId(), e.details(), e.prevHash(), e.entryHash());
    }
}

package com.team404.corrosion.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChainVerifierTest {

    private List<AuditEntry> chain;

    @BeforeEach
    void buildChain() {
        chain = new ArrayList<>(TestChains.chain(5));
    }

    @Test
    void validChainVerifies() {
        VerificationResult result = ChainVerifier.verify(chain.iterator());
        assertTrue(result.valid());
        assertEquals(5L, result.entriesChecked());
        assertNull(result.firstBrokenId());
    }

    @Test
    void emptyChainIsValid() {
        VerificationResult result = ChainVerifier.verify(List.<AuditEntry>of().iterator());
        assertTrue(result.valid());
        assertEquals(0L, result.entriesChecked());
    }

    @Test
    void editedEntryIsDetectedAtThatEntry() {
        chain.set(2, TestChains.withActor(chain.get(2), "mallory"));
        VerificationResult result = ChainVerifier.verify(chain.iterator());
        assertFalse(result.valid());
        assertEquals(Long.valueOf(3), result.firstBrokenId());
        assertEquals(2L, result.entriesChecked());
    }

    @Test
    void deletedEntryIsDetected() {
        chain.remove(1);
        VerificationResult result = ChainVerifier.verify(chain.iterator());
        assertFalse(result.valid());
        assertEquals(Long.valueOf(2), result.firstBrokenId());
    }

    @Test
    void reorderedEntriesAreDetected() {
        AuditEntry second = chain.get(1);
        chain.set(1, chain.get(2));
        chain.set(2, second);
        assertFalse(ChainVerifier.verify(chain.iterator()).valid());
    }

    @Test
    void entryWithWrongPrevHashIsDetected() {
        AuditEntry third = chain.get(2);
        AuditEntry wrongPrev = new AuditEntry(third.id(), third.outboxId(), third.occurredAt(), third.recordedAt(),
                third.actorType(), third.actor(), third.action(), third.entityType(), third.entityId(),
                third.details(), "f".repeat(64), null);
        chain.set(2, wrongPrev.withEntryHash(HashChain.computeHash(wrongPrev)));
        VerificationResult result = ChainVerifier.verify(chain.iterator());
        assertFalse(result.valid());
        assertEquals(Long.valueOf(3), result.firstBrokenId());
    }

    @Test
    void rewritingOneEntryAndItsHashStillBreaksTheNextLink() {
        AuditEntry second = chain.get(1);
        AuditEntry edited = new AuditEntry(second.id(), second.outboxId(), second.occurredAt(), second.recordedAt(),
                second.actorType(), "mallory", second.action(), second.entityType(), second.entityId(),
                second.details(), second.prevHash(), null);
        chain.set(1, edited.withEntryHash(HashChain.computeHash(edited)));
        VerificationResult result = ChainVerifier.verify(chain.iterator());
        assertFalse(result.valid());
        assertEquals(Long.valueOf(3), result.firstBrokenId());
    }

    @Test
    void chainMatchingTheHeadPasses() {
        AuditEntry last = chain.get(4);
        assertTrue(ChainVerifier.verifyAgainstHead(chain.iterator(), last.id(), last.entryHash()).valid());
    }

    @Test
    void emptyChainMatchesTheGenesisHead() {
        assertTrue(ChainVerifier.verifyAgainstHead(List.<AuditEntry>of().iterator(), 0, HashChain.GENESIS_HASH)
                .valid());
    }

    @Test
    void entriesRemovedFromTheEndAreDetectedAgainstTheHead() {
        AuditEntry realLast = chain.get(4);
        chain.remove(4);
        assertTrue(ChainVerifier.verify(chain.iterator()).valid());
        VerificationResult result =
                ChainVerifier.verifyAgainstHead(chain.iterator(), realLast.id(), realLast.entryHash());
        assertFalse(result.valid());
        assertEquals(Long.valueOf(5), result.firstBrokenId());
    }

    @Test
    void headWithTheRightIdButWrongHashIsDetected() {
        AuditEntry last = chain.get(4);
        assertFalse(ChainVerifier.verifyAgainstHead(chain.iterator(), last.id(), "a".repeat(64)).valid());
    }

    @Test
    void brokenChainIsReportedBeforeTheHeadIsChecked() {
        chain.set(0, TestChains.withActor(chain.get(0), "mallory"));
        AuditEntry last = chain.get(4);
        VerificationResult result = ChainVerifier.verifyAgainstHead(chain.iterator(), last.id(), last.entryHash());
        assertFalse(result.valid());
        assertEquals(Long.valueOf(1), result.firstBrokenId());
    }
}

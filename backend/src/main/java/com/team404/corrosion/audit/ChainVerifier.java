package com.team404.corrosion.audit;

import java.util.Iterator;

/** Walks audit entries in id order and checks the chain. No database access, so it is easy to test. */
public final class ChainVerifier {

    private ChainVerifier() {
    }

    /**
     * Checks, for every entry: the id is the next number in order (catches deleted or reordered rows), prev_hash equals
     * the previous entry's hash (catches broken links), and recomputing the hash gives entry_hash (catches edits).
     */
    public static VerificationResult verify(Iterator<AuditEntry> entries) {
        String expectedPrev = HashChain.GENESIS_HASH;
        long expectedId = 1;
        long checked = 0;

        while (entries.hasNext()) {
            AuditEntry e = entries.next();

            if (e.id() != expectedId) {
                return VerificationResult.broken(checked, expectedId,
                        "Missing or out-of-order entry: expected id " + expectedId + " but found " + e.id());
            }
            if (!e.prevHash().equals(expectedPrev)) {
                return VerificationResult.broken(checked, e.id(),
                        "prev_hash does not match the previous entry's hash");
            }
            if (!HashChain.computeHash(e).equals(e.entryHash())) {
                return VerificationResult.broken(checked, e.id(),
                        "Entry contents do not match entry_hash (row was modified)");
            }

            expectedPrev = e.entryHash();
            expectedId++;
            checked++;
        }
        return VerificationResult.ok(checked);
    }

    /**
     * Same as {@link #verify}, and also requires the chain to end exactly at the recorded head. Without this check,
     * entries chopped off the end would leave a chain that still looks valid.
     */
    public static VerificationResult verifyAgainstHead(Iterator<AuditEntry> entries, long headLastId,
                                                       String headLastHash) {
        Tracking tracking = new Tracking(entries);
        VerificationResult base = verify(tracking);
        if (!base.valid()) {
            return base;
        }
        long lastId = tracking.last == null ? 0 : tracking.last.id();
        String lastHash = tracking.last == null ? HashChain.GENESIS_HASH : tracking.last.entryHash();
        if (lastId != headLastId || !lastHash.equals(headLastHash)) {
            return VerificationResult.broken(base.entriesChecked(), lastId + 1,
                    "Chain ends before the recorded head: entries may have been removed from the end");
        }
        return base;
    }

    /** Remembers the last entry that was handed out. */
    private static final class Tracking implements Iterator<AuditEntry> {
        private final Iterator<AuditEntry> source;
        private AuditEntry last;

        Tracking(Iterator<AuditEntry> source) {
            this.source = source;
        }

        @Override
        public boolean hasNext() {
            return source.hasNext();
        }

        @Override
        public AuditEntry next() {
            last = source.next();
            return last;
        }
    }
}

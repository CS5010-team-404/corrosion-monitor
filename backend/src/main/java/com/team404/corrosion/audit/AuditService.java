package com.team404.corrosion.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Verification, checkpoints and browsing of the audit log. Chaining itself is done by {@link AuditChainWorker}. */
@Service
public class AuditService {

    static final int MAX_PAGE_SIZE = 200;
    private static final int VERIFY_BATCH = 1000;
    private static final int CHECKPOINTS_CHECKED = 100;
    /** audit_outbox.actor is VARCHAR(64), as in LoginAudit. */
    private static final int ACTOR_MAX = 64;

    public record PageResult<T>(List<T> items, int page, int size, long total) {
    }

    private final AuditStore store;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(AuditStore store, ObjectMapper json, Clock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Walks the whole chain up to the head as it was when the run started (entries added meanwhile are ignored), checks
     * it ends at that head, and checks that it still matches every saved checkpoint. The run is stored, and the audit
     * service records it as an AUDIT_VERIFIED event of its own.
     */
    public VerificationResult verify(String runBy) {
        AuditStore.Head head = store.readHead();
        VerificationResult result = ChainVerifier.verifyAgainstHead(new BatchedEntries(head.lastId()),
                head.lastId(), head.lastHash());
        if (result.valid()) {
            result = checkCheckpoints(result);
        }
        store.saveVerification(now(), runBy, result);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("valid", result.valid());
        details.put("entriesChecked", result.entriesChecked());
        if (result.firstBrokenId() != null) {
            details.put("firstBrokenId", result.firstBrokenId());
        }
        recordOutbox(runBy, "AUDIT_VERIFIED", "audit", null, details);
        return result;
    }

    /** Saves the current head. Also run on a schedule, and worth copying somewhere the application cannot write. */
    public void saveCheckpoint() {
        store.saveCheckpoint(now());
    }

    public List<AuditStore.Checkpoint> recentCheckpoints() {
        return store.recentCheckpoints(50);
    }

    public List<AuditStore.VerificationRun> recentVerifications() {
        return store.recentVerifications(20);
    }

    public PageResult<AuditEntry> search(AuditStore.Filter filter, int page, int size) {
        int s = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int p = Math.max(0, page);
        return new PageResult<>(store.search(filter, s, p * s), p, s, store.count(filter));
    }

    /** Records a refused request (for example a non-admin calling an admin endpoint). */
    public void recordDenied(String subject, String endpoint) {
        recordOutbox(subject == null ? "anonymous" : subject, "ACCESS_DENIED", "audit", null,
                Map.of("endpoint", endpoint));
    }

    private VerificationResult checkCheckpoints(VerificationResult chain) {
        for (AuditStore.Checkpoint cp : store.recentCheckpoints(CHECKPOINTS_CHECKED)) {
            if (cp.lastId() == 0) {
                continue;
            }
            Optional<String> actual = store.hashOf(cp.lastId());
            if (actual.isEmpty() || !actual.get().equals(cp.lastHash())) {
                return VerificationResult.broken(chain.entriesChecked(), cp.lastId(),
                        "Chain no longer matches the checkpoint saved at " + cp.createdAt());
            }
        }
        return chain;
    }

    private void recordOutbox(String actor, String action, String entityType, String entityId,
                              Map<String, Object> details) {
        String who = actor == null || actor.isBlank() ? "unknown" : actor;
        if (who.length() > ACTOR_MAX) {
            who = who.substring(0, ACTOR_MAX);
        }
        try {
            store.insertOutbox(now(), who, action, entityType, entityId, json.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit details are not serialisable", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /** Reads audit_log in id order, VERIFY_BATCH rows at a time, up to and including upToId. */
    private final class BatchedEntries implements Iterator<AuditEntry> {
        private final long upToId;
        private long afterId = 0;
        private Iterator<AuditEntry> current = List.<AuditEntry>of().iterator();
        private boolean exhausted = false;

        BatchedEntries(long upToId) {
            this.upToId = upToId;
        }

        @Override
        public boolean hasNext() {
            if (current.hasNext()) {
                return true;
            }
            if (exhausted || afterId >= upToId) {
                return false;
            }
            List<AuditEntry> batch = store.readRange(afterId, upToId, VERIFY_BATCH);
            if (batch.isEmpty()) {
                exhausted = true;
                return false;
            }
            afterId = batch.get(batch.size() - 1).id();
            current = batch.iterator();
            return true;
        }

        @Override
        public AuditEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return current.next();
        }
    }
}

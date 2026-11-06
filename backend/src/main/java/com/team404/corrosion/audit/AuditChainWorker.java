package com.team404.corrosion.audit;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only writer of audit_log. Picks up audit_outbox rows whose chained_at is still null and chains them in
 * outbox-id order. Each batch is one transaction holding the lock on audit_chain_head: the entries, the new head and
 * the chained_at marks all commit together or not at all, so a crash leaves nothing half-done. A row that commits late
 * with a lower id is simply picked up on the next run, because its chained_at is still null. Running the worker twice
 * is safe (audit_log.outbox_id is unique).
 */
@Component
public class AuditChainWorker {

    static final int BATCH_SIZE = 200;

    private final AuditStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuditChainWorker(AuditStore store, PlatformTransactionManager txManager, Clock clock) {
        this.store = store;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Chains everything that is pending, batch by batch. Returns how many entries were added. */
    public int drainAll() {
        int total = 0;
        int chained;
        do {
            chained = drainOnce();
            total += chained;
        } while (chained == BATCH_SIZE);
        return total;
    }

    /** Chains at most one batch. Returns how many entries were added. */
    public int drainOnce() {
        Integer chained = tx.execute(status -> {
            AuditStore.Head head = store.lockHead();
            List<OutboxEvent> pending = store.findPending(BATCH_SIZE);
            if (pending.isEmpty()) {
                return 0;
            }
            long id = head.lastId();
            String prev = head.lastHash();
            for (OutboxEvent e : pending) {
                id++;
                LocalDateTime recorded = now();
                AuditEntry unsigned = new AuditEntry(id, e.id(), e.occurredAt(), recorded, e.actorType(), e.actor(),
                        e.action(), e.entityType(), e.entityId(), e.details(), prev, null);
                AuditEntry signed = unsigned.withEntryHash(HashChain.computeHash(unsigned));
                store.insert(signed);
                store.markChained(e.id(), recorded);
                prev = signed.entryHash();
            }
            store.updateHead(id, prev);
            return pending.size();
        });
        return chained == null ? 0 : chained;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}

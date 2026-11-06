package com.team404.corrosion.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * All SQL for the audit service. Reads audit_outbox (it never changes it, except for the self-audit rows written by
 * {@link #insertOutbox}) and owns audit_log, audit_chain_head, audit_verification and audit_checkpoint.
 *
 * <p>audit_outbox is the contract table from migration V2: id, occurred_at, actor_type, actor, action, entity_type,
 * entity_id, details, and chained_at, which this service sets once a row is in the chain.
 */
@Repository
public class AuditStore {

    public record Head(long lastId, String lastHash) {
    }

    public record Checkpoint(long id, Instant createdAt, long lastId, String lastHash) {
    }

    public record VerificationRun(long id, Instant runAt, String runBy, long entriesChecked, boolean valid,
                                  Long firstBrokenId, String message) {
    }

    /** Optional filters for browsing the log; null means "any". */
    public record Filter(String actor, String action, String entityType, LocalDateTime from, LocalDateTime to) {
        public static final Filter NONE = new Filter(null, null, null, null, null);
    }

    private static final String ENTRY_COLUMNS = "id, outbox_id, occurred_at, recorded_at, actor_type, actor, action, "
            + "entity_type, entity_id, details, prev_hash, entry_hash";

    /** Rows other services have written that the worker has not chained yet (chained_at is still null). */
    private static final String PENDING_SQL = """
            SELECT id, occurred_at, actor_type, actor, action, entity_type, entity_id, details
            FROM audit_outbox
            WHERE chained_at IS NULL
            ORDER BY id
            LIMIT ?
            """;

    private static final RowMapper<Head> HEAD_MAPPER =
            (rs, i) -> new Head(rs.getLong("last_id"), rs.getString("last_hash"));

    private static final RowMapper<OutboxEvent> OUTBOX_MAPPER = (rs, i) -> new OutboxEvent(
            rs.getLong("id"),
            time(rs, "occurred_at"),
            rs.getString("actor_type"),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getString("details"));

    private static final RowMapper<AuditEntry> ENTRY_MAPPER = (rs, i) -> new AuditEntry(
            rs.getLong("id"),
            rs.getLong("outbox_id"),
            time(rs, "occurred_at"),
            time(rs, "recorded_at"),
            rs.getString("actor_type"),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getString("details"),
            rs.getString("prev_hash"),
            rs.getString("entry_hash"));

    private static final RowMapper<Checkpoint> CHECKPOINT_MAPPER = (rs, i) -> new Checkpoint(
            rs.getLong("id"),
            utc(time(rs, "created_at")),
            rs.getLong("last_id"),
            rs.getString("last_hash"));

    private static final RowMapper<VerificationRun> RUN_MAPPER = (rs, i) -> new VerificationRun(
            rs.getLong("id"),
            utc(time(rs, "run_at")),
            rs.getString("run_by"),
            rs.getLong("entries_checked"),
            rs.getBoolean("valid"),
            rs.getObject("first_broken_id", Long.class),
            rs.getString("message"));

    private final JdbcTemplate jdbc;

    public AuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- chain head ----

    /** Reads the head and locks its row until the transaction ends: this is what makes chaining single-file. */
    public Head lockHead() {
        return jdbc.queryForObject("SELECT last_id, last_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE",
                HEAD_MAPPER);
    }

    public Head readHead() {
        return jdbc.queryForObject("SELECT last_id, last_hash FROM audit_chain_head WHERE id = 1", HEAD_MAPPER);
    }

    public void updateHead(long lastId, String lastHash) {
        jdbc.update("UPDATE audit_chain_head SET last_id = ?, last_hash = ? WHERE id = 1", lastId, lastHash);
    }

    // ---- outbox and log ----

    public List<OutboxEvent> findPending(int limit) {
        return jdbc.query(PENDING_SQL, OUTBOX_MAPPER, limit);
    }

    /** Marks an outbox row as chained. Done in the same transaction as the audit_log insert. */
    public void markChained(long outboxId, LocalDateTime at) {
        jdbc.update("UPDATE audit_outbox SET chained_at = ? WHERE id = ?", at, outboxId);
    }

    public void insert(AuditEntry e) {
        jdbc.update("INSERT INTO audit_log (" + ENTRY_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.id(), e.outboxId(), e.occurredAt(), e.recordedAt(), e.actorType(), e.actor(), e.action(),
                e.entityType(), e.entityId(), e.details(), e.prevHash(), e.entryHash());
    }

    /** Entries with afterId &lt; id &lt;= upToId, in id order. */
    public List<AuditEntry> readRange(long afterId, long upToId, int limit) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + " FROM audit_log WHERE id > ? AND id <= ? ORDER BY id LIMIT ?",
                ENTRY_MAPPER, afterId, upToId, limit);
    }

    public Optional<String> hashOf(long id) {
        List<String> found = jdbc.query("SELECT entry_hash FROM audit_log WHERE id = ?",
                (rs, i) -> rs.getString(1), id);
        return found.stream().findFirst();
    }

    /** Newest first. */
    public List<AuditEntry> search(Filter filter, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT " + ENTRY_COLUMNS + " FROM audit_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                ENTRY_MAPPER, args.toArray());
    }

    public long count(Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(Filter f, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (f.actor() != null) {
            clauses.add("actor = ?");
            args.add(f.actor());
        }
        if (f.action() != null) {
            clauses.add("action = ?");
            args.add(f.action());
        }
        if (f.entityType() != null) {
            clauses.add("entity_type = ?");
            args.add(f.entityType());
        }
        if (f.from() != null) {
            clauses.add("occurred_at >= ?");
            args.add(f.from());
        }
        if (f.to() != null) {
            clauses.add("occurred_at < ?");
            args.add(f.to());
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    // ---- verification runs and checkpoints ----

    public void saveVerification(LocalDateTime runAt, String runBy, VerificationResult result) {
        String message = result.message();
        if (message != null && message.length() > 255) {
            message = message.substring(0, 255);
        }
        jdbc.update("INSERT INTO audit_verification (run_at, run_by, entries_checked, valid, first_broken_id, message) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                runAt, runBy, result.entriesChecked(), result.valid(), result.firstBrokenId(), message);
    }

    public List<VerificationRun> recentVerifications(int limit) {
        return jdbc.query("SELECT id, run_at, run_by, entries_checked, valid, first_broken_id, message "
                + "FROM audit_verification ORDER BY id DESC LIMIT ?", RUN_MAPPER, limit);
    }

    /** Copies the current head into audit_checkpoint. */
    public void saveCheckpoint(LocalDateTime createdAt) {
        jdbc.update("INSERT INTO audit_checkpoint (created_at, last_id, last_hash) "
                + "SELECT ?, last_id, last_hash FROM audit_chain_head WHERE id = 1", createdAt);
    }

    public List<Checkpoint> recentCheckpoints(int limit) {
        return jdbc.query("SELECT id, created_at, last_id, last_hash FROM audit_checkpoint ORDER BY id DESC LIMIT ?",
                CHECKPOINT_MAPPER, limit);
    }

    // ---- the audit service's own events ----

    /** Same columns and shape as LoginAudit, so the audit service's own actions are chained like everyone else's. */
    public void insertOutbox(LocalDateTime occurredAt, String actor, String action, String entityType,
                             String entityId, String detailsJson) {
        jdbc.update("INSERT INTO audit_outbox (occurred_at, actor_type, actor, action, entity_type, entity_id, details) "
                + "VALUES (?, 'USER', ?, ?, ?, ?, ?)", occurredAt, actor, action, entityType, entityId, detailsJson);
    }

    // ---- helpers ----

    /** Reads a DATETIME as-is, without any time-zone shift. */
    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private static Instant utc(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}

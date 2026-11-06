package com.team404.corrosion.alert.signal;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAlertStore implements AlertStore {

    private static final int TITLE_MAX = 160;
    private static final int MESSAGE_MAX = 500;

    private final JdbcTemplate jdbc;

    public JdbcAlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredAlert> findUnresolved(String dedupKey) {
        List<StoredAlert> found = jdbc.query(
                "SELECT id, condition_cleared_at FROM alert WHERE active_dedup_key = ?",
                (rs, row) -> {
                    Timestamp cleared = rs.getTimestamp("condition_cleared_at");
                    return new StoredAlert(rs.getLong("id"), cleared == null ? null : cleared.toInstant());
                }, dedupKey);
        return found.stream().findFirst();
    }

    @Override
    public long open(Signal s, Instant openedAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                INSERT INTO alert (location_id, equipment_id, sensor_id, device_id, source, type, severity, title, message,
                                   dedup_key, status, opened_at, critical_since)
                VALUES ((SELECT location_id FROM equipment WHERE id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            Object[] args = {s.equipmentId(), s.equipmentId(), s.sensorId(), s.deviceId(), s.source(), s.type(),
                    severity(s), title(s), message(s), s.dedupKey(), Timestamp.from(openedAt),
                    isCritical(s) ? Timestamp.from(openedAt) : null};
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    @Override
    public void refresh(long alertId, Signal s, Instant at) {
        // critical_since is set before severity changes, so it still sees the old severity.
        jdbc.update("""
                UPDATE alert SET
                    critical_since = CASE WHEN ? THEN (CASE WHEN severity = 'CRITICAL' THEN COALESCE(critical_since, opened_at) ELSE ? END) END,
                    severity = ?, title = ?, message = ?, sensor_id = ?, condition_cleared_at = NULL
                WHERE id = ?
                """, isCritical(s), Timestamp.from(at), severity(s), title(s), message(s), s.sensorId(), alertId);
    }

    @Override
    public void markConditionCleared(long alertId, Instant clearedAt) {
        jdbc.update("UPDATE alert SET condition_cleared_at = ? WHERE id = ?", Timestamp.from(clearedAt), alertId);
    }

    @Override
    public List<Long> resolveQuiet(Instant clearedBefore, Instant resolvedAt) {
        List<Long> due = jdbc.queryForList("""
                SELECT id FROM alert
                WHERE status <> 'RESOLVED' AND condition_cleared_at IS NOT NULL AND condition_cleared_at <= ?
                ORDER BY id
                """, Long.class, Timestamp.from(clearedBefore));
        // One guarded UPDATE per alert: if a person resolved it in between, it is not reported as auto-resolved.
        return due.stream().filter(id -> jdbc.update(
                "UPDATE alert SET status = 'RESOLVED', resolved_at = ? WHERE id = ? AND status <> 'RESOLVED'",
                Timestamp.from(resolvedAt), id) == 1).toList();
    }

    /** alert.severity allows WARNING and CRITICAL only; an INFO signal is shown as a warning. */
    private static String severity(Signal s) {
        return isCritical(s) ? "CRITICAL" : "WARNING";
    }

    private static boolean isCritical(Signal s) {
        return "CRITICAL".equals(s.severity());
    }

    private static String title(Signal s) {
        return truncate(s.title() != null ? s.title() : s.type(), TITLE_MAX);
    }

    /** alert.message is NOT NULL. */
    private static String message(Signal s) {
        return truncate(s.message() != null ? s.message() : title(s), MESSAGE_MAX);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}

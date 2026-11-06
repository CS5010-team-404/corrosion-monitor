package com.team404.corrosion.alert.lifecycle;

import com.team404.corrosion.alert.lifecycle.AlertViews.ActionView;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every state change is one conditional UPDATE (WHERE status = the expected one), so two people acting on the same
 * alert cannot both succeed, and no read-then-write race exists.
 */
@Repository
public class AlertRepository {

    private static final String SELECT = """
            SELECT a.id, a.type, a.source, a.severity, a.status, a.escalation_level, a.equipment_id,
                   e.name AS equipment_name, l.name AS location_name, a.sensor_id, a.device_id,
                   COALESCE(a.title, a.type) AS title, a.message, a.opened_at,
                   a.acknowledged_at, ack.name AS acknowledged_by,
                   a.resolved_at, CASE WHEN a.resolved_at IS NULL THEN NULL ELSE COALESCE(res.name, 'System') END AS resolved_by,
                   a.condition_cleared_at
            FROM alert a
            LEFT JOIN equipment e ON e.id = a.equipment_id
            LEFT JOIN location l ON l.id = a.location_id
            LEFT JOIN app_user ack ON ack.id = a.acknowledged_by
            LEFT JOIN app_user res ON res.id = a.resolved_by
            """;

    private static final String CAN_ACKNOWLEDGE = AlertStatus.sqlIn(AlertStatus::canAcknowledge);
    private static final String CAN_RESOLVE = AlertStatus.sqlIn(AlertStatus::canResolve);
    private static final String CAN_LOG_ACTION = AlertStatus.sqlIn(AlertStatus::canLogAction);
    private static final String CAN_ESCALATE = AlertStatus.sqlIn(AlertStatus::canEscalate);

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code statuses} is the filter already translated to concrete statuses; empty means all. */
    public List<AlertView> list(List<AlertStatus> statuses, int limit) {
        String where = statuses.isEmpty() ? ""
                : " WHERE a.status IN (" + String.join(",", statuses.stream().map(s -> "'" + s.name() + "'").toList()) + ")";
        return jdbc.query(SELECT + where + " ORDER BY a.opened_at DESC, a.id DESC LIMIT ?", this::alert, limit);
    }

    public Optional<AlertView> find(long id) {
        return jdbc.query(SELECT + " WHERE a.id = ?", this::alert, id).stream().findFirst();
    }

    public Optional<AlertStatus> statusOf(long id) {
        return jdbc.queryForList("SELECT status FROM alert WHERE id = ?", String.class, id).stream().findFirst()
                .map(AlertStatus::valueOf);
    }

    public List<ActionView> actions(long alertId) {
        return jdbc.query("""
                SELECT c.id, c.action_type, c.notes, u.name, c.created_at
                FROM corrective_action c JOIN app_user u ON u.id = c.user_id
                WHERE c.alert_id = ? ORDER BY c.created_at, c.id
                """, (rs, row) -> new ActionView(rs.getLong("id"), rs.getString("action_type"), rs.getString("notes"),
                rs.getString("name"), instant(rs, "created_at")), alertId);
    }

    public Optional<Long> userIdByEmail(String email) {
        return jdbc.queryForList("SELECT id FROM app_user WHERE email = ?", Long.class, email).stream().findFirst();
    }

    /** True if the alert was OPEN and now is ACKNOWLEDGED. */
    public boolean acknowledge(long id, long userId, Instant at) {
        return jdbc.update("""
                UPDATE alert SET status = 'ACKNOWLEDGED', acknowledged_by = ?, acknowledged_at = ?
                WHERE id = ? AND status IN %s
                """.formatted(CAN_ACKNOWLEDGE), userId, Timestamp.from(at), id) == 1;
    }

    /** True if the alert was not yet RESOLVED and now is. */
    public boolean resolve(long id, long userId, Instant at) {
        return jdbc.update("""
                UPDATE alert SET status = 'RESOLVED', resolved_by = ?, resolved_at = ?
                WHERE id = ? AND status IN %s
                """.formatted(CAN_RESOLVE), userId, Timestamp.from(at), id) == 1;
    }

    /** Inserts only while the alert is not RESOLVED. Returns the new id, or empty if the alert was resolved or is gone. */
    public Optional<Long> addAction(long alertId, long userId, String actionType, String notes, Instant at) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        int rows = jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO corrective_action (alert_id, user_id, action_type, notes, created_at)
                    SELECT id, ?, ?, ?, ? FROM alert WHERE id = ? AND status IN %s
                    """.formatted(CAN_LOG_ACTION), java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, actionType);
            ps.setString(3, notes);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setLong(5, alertId);
            return ps;
        }, keys);
        return rows == 1 ? Optional.of(keys.getKey().longValue()) : Optional.empty();
    }

    public Optional<ActionView> action(long id) {
        return jdbc.query("""
                SELECT c.id, c.action_type, c.notes, u.name, c.created_at
                FROM corrective_action c JOIN app_user u ON u.id = c.user_id WHERE c.id = ?
                """, (rs, row) -> new ActionView(rs.getLong("id"), rs.getString("action_type"), rs.getString("notes"),
                rs.getString("name"), instant(rs, "created_at")), id).stream().findFirst();
    }

    /** OPEN CRITICAL alerts not yet escalated that have been CRITICAL since the cutoff or earlier. */
    public List<Long> escalationCandidates(Instant openedBefore) {
        return jdbc.queryForList("""
                SELECT id FROM alert
                WHERE status IN %s AND severity = 'CRITICAL' AND escalation_level = 0
                  AND COALESCE(critical_since, opened_at) <= ?
                ORDER BY id
                """.formatted(CAN_ESCALATE), Long.class, Timestamp.from(openedBefore));
    }

    /** True if this call escalated it (it was still OPEN and at level 0). */
    public boolean escalate(long id, Instant at) {
        return jdbc.update("""
                UPDATE alert SET escalation_level = 1, escalated_at = ?
                WHERE id = ? AND status IN %s AND severity = 'CRITICAL' AND escalation_level = 0
                """.formatted(CAN_ESCALATE), Timestamp.from(at), id) == 1;
    }

    public List<String> managerEmails() {
        return jdbc.queryForList("SELECT email FROM app_user WHERE role = 'MANAGER' ORDER BY id", String.class);
    }

    private AlertView alert(ResultSet rs, int row) throws SQLException {
        return new AlertView(rs.getLong("id"), rs.getString("type"), rs.getString("source"), rs.getString("severity"),
                AlertStatus.valueOf(rs.getString("status")), rs.getInt("escalation_level"),
                (Long) rs.getObject("equipment_id"), rs.getString("equipment_name"), rs.getString("location_name"),
                (Long) rs.getObject("sensor_id"), rs.getString("device_id"), rs.getString("title"),
                rs.getString("message"), instant(rs, "opened_at"), instant(rs, "acknowledged_at"),
                rs.getString("acknowledged_by"), instant(rs, "resolved_at"), rs.getString("resolved_by"),
                instant(rs, "condition_cleared_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}

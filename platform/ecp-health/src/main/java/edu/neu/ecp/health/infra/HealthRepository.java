package edu.neu.ecp.health.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.neu.ecp.health.domain.HealthAssessment;
import edu.neu.ecp.health.domain.HealthIssue;
import edu.neu.ecp.health.domain.ReadingSample;
import edu.neu.ecp.health.domain.SensorRef;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** All SQL the health evaluation loop needs. Reads shared tables; writes only sensor_health tables. */
@Repository
public class HealthRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public HealthRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<SensorRef> activeSensors() {
        return jdbc.query("""
                SELECT id, device_id, equipment_id, expected_interval_s, created_at
                FROM sensors WHERE active = TRUE ORDER BY id
                """, (rs, i) -> new SensorRef(rs.getLong("id"), rs.getString("device_id"),
                (Long) rs.getObject("equipment_id", Long.class), Duration.ofSeconds(rs.getInt("expected_interval_s")),
                instant(rs, "created_at")));
    }

    /** Accepted readings received after {@code since}, grouped by sensor. One query for all sensors. */
    public Map<Long, List<ReadingSample>> windows(Instant since) {
        Map<Long, List<ReadingSample>> out = new HashMap<>();
        jdbc.query("""
                SELECT sensor_id, seq_no, measured_at, received_at, temperature_c, humidity_pct
                FROM readings WHERE received_at > ? ORDER BY sensor_id, received_at
                """, rs -> {
            out.computeIfAbsent(rs.getLong("sensor_id"), k -> new ArrayList<>()).add(new ReadingSample(
                    rs.getLong("seq_no"), instant(rs, "measured_at"), instant(rs, "received_at"),
                    rs.getDouble("temperature_c"), rs.getDouble("humidity_pct")));
        }, Timestamp.from(since));
        return out;
    }

    /** Fallback for a sensor with no health row and no recent readings (first run after a long outage). */
    public Instant lastSeenFromReadings(long sensorId) {
        Timestamp t = jdbc.queryForObject("SELECT MAX(received_at) FROM readings WHERE sensor_id = ?",
                Timestamp.class, sensorId);
        return t == null ? null : t.toInstant();
    }

    public Map<String, Integer> signatureFailures(Instant since) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("""
                SELECT device_id, COUNT(*) AS n FROM ingest_rejections
                WHERE reason = 'BAD_SIGNATURE' AND received_at > ? GROUP BY device_id
                """, rs -> {
            out.put(rs.getString("device_id"), rs.getInt("n"));
        }, Timestamp.from(since));
        return out;
    }

    public record StoredHealth(String status, Instant lastSeenAt, Instant statusSince) {
    }

    public Map<Long, StoredHealth> currentHealth() {
        Map<Long, StoredHealth> out = new HashMap<>();
        jdbc.query("SELECT sensor_id, status, last_seen_at, status_since FROM sensor_health", rs -> {
            out.put(rs.getLong("sensor_id"), new StoredHealth(rs.getString("status"), instant(rs, "last_seen_at"),
                    instant(rs, "status_since")));
        });
        return out;
    }

    /** Open episodes: sensor -> issue -> event id. */
    public Map<Long, Map<HealthIssue, Long>> openEpisodes() {
        Map<Long, Map<HealthIssue, Long>> out = new HashMap<>();
        jdbc.query("SELECT id, sensor_id, issue FROM sensor_health_events WHERE ended_at IS NULL", rs -> {
            HealthIssue issue = parseIssue(rs.getString("issue"));
            if (issue != null) {
                out.computeIfAbsent(rs.getLong("sensor_id"), k -> new EnumMap<>(HealthIssue.class))
                        .put(issue, rs.getLong("id"));
            }
        });
        return out;
    }

    public long openEpisode(long sensorId, HealthIssue issue, Instant startedAt, Map<String, Object> details) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO sensor_health_events (sensor_id, issue, severity, started_at, details)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sensorId);
            ps.setString(2, issue.name());
            ps.setString(3, issue.severity().name());
            ps.setTimestamp(4, Timestamp.from(startedAt));
            ps.setString(5, toJson(details));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public void closeEpisode(long eventId, Instant endedAt) {
        jdbc.update("UPDATE sensor_health_events SET ended_at = ? WHERE id = ? AND ended_at IS NULL",
                Timestamp.from(endedAt), eventId);
    }

    public void upsertHealth(HealthAssessment a, Instant statusSince, int signatureFailures) {
        var m = a.evaluation().metrics();
        List<String> issues = a.activeIssues().stream().map(Enum::name).sorted().toList();
        jdbc.update("""
                INSERT INTO sensor_health (sensor_id, status, connectivity, trusted, health_score, issues, last_seen_at,
                    last_measured_at, last_seq_no, window_readings, window_missing_pct, signature_failures,
                    status_since, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), connectivity = VALUES(connectivity),
                    trusted = VALUES(trusted), health_score = VALUES(health_score), issues = VALUES(issues),
                    last_seen_at = VALUES(last_seen_at), last_measured_at = COALESCE(VALUES(last_measured_at), last_measured_at),
                    last_seq_no = COALESCE(VALUES(last_seq_no), last_seq_no), window_readings = VALUES(window_readings),
                    window_missing_pct = VALUES(window_missing_pct), signature_failures = VALUES(signature_failures),
                    status_since = VALUES(status_since), evaluated_at = VALUES(evaluated_at)
                """,
                a.sensorId(), a.status().name(), a.connectivity().name(), a.trusted(), a.score(), toJson(issues),
                ts(m.lastSeenAt()), ts(m.lastMeasuredAt()), m.lastSeqNo(), m.readings(), m.missingPct(),
                signatureFailures, Timestamp.from(statusSince), Timestamp.from(a.evaluation().evaluatedAt()));
    }

    // ------------------------------------------------------------------------------------------------

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static HealthIssue parseIssue(String s) {
        try {
            return HealthIssue.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null; // an issue type removed from code: ignore old rows
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}

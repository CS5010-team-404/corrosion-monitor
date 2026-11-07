package edu.neu.ecp.health.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read models for the API and dashboard. Joins sensors with health, equipment and location names. */
@Repository
public class HealthQueries {

    // Equipment and location names are stored on the platform's own sensors table, so these queries never
    // depend on the team backend's schema. Join the team's equipment table instead if you integrate more tightly.
    private static final String VIEW_SQL = """
            SELECT s.id, s.device_id, s.kind, s.model, s.expected_interval_s, s.equipment_id,
                   COALESCE(s.equipment_name, '') AS equipment_name, COALESCE(s.location_name, '') AS location_name,
                   COALESCE(h.status, 'UNKNOWN') AS status, COALESCE(h.connectivity, 'NEVER_SEEN') AS connectivity,
                   COALESCE(h.health_score, 0) AS health_score, COALESCE(h.trusted, FALSE) AS trusted,
                   h.issues, h.last_seen_at, h.last_measured_at, h.last_seq_no, h.window_readings,
                   h.window_missing_pct, h.signature_failures, h.status_since, h.evaluated_at
            FROM sensors s
            LEFT JOIN sensor_health h ON h.sensor_id = s.id
            WHERE s.active = TRUE
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public HealthQueries(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record SensorHealthView(long sensorId, String deviceId, String kind, String model, long expectedIntervalSeconds,
                                   Long equipmentId, String equipmentName, String locationName, String status,
                                   String connectivity, int score, boolean trusted, List<String> issues,
                                   Instant lastSeenAt, Instant lastMeasuredAt, Long lastSeqNo, Integer windowReadings,
                                   Double missingPct, Integer signatureFailures, Instant statusSince,
                                   Instant evaluatedAt) {
    }

    public record HealthEventView(long id, long sensorId, String deviceId, String issue, String severity,
                                  Instant startedAt, Instant endedAt, long durationSeconds, Map<String, Object> details) {
    }

    public record ReadingPoint(Instant measuredAt, Instant receivedAt, long seqNo, double temperatureC,
                               double humidityPct) {
    }

    public record ServiceView(String service, String state, Instant lastRunAt, Instant lastSuccessAt,
                              String lastError, Instant lastErrorAt, long runCount, long errorCount) {
    }

    public List<SensorHealthView> sensors() {
        return jdbc.query(VIEW_SQL + " ORDER BY s.location_name, s.equipment_name, s.device_id", this::mapView);
    }

    public Optional<SensorHealthView> sensor(long sensorId) {
        return jdbc.query(VIEW_SQL + " AND s.id = ?", this::mapView, sensorId).stream().findFirst();
    }

    public List<HealthEventView> events(Long sensorId, boolean openOnly, int limit, Instant now) {
        StringBuilder sql = new StringBuilder("""
                SELECT ev.id, ev.sensor_id, s.device_id, ev.issue, ev.severity, ev.started_at, ev.ended_at, ev.details
                FROM sensor_health_events ev JOIN sensors s ON s.id = ev.sensor_id WHERE 1 = 1
                """);
        if (sensorId != null) {
            sql.append(" AND ev.sensor_id = ").append(sensorId.longValue());
        }
        if (openOnly) {
            sql.append(" AND ev.ended_at IS NULL");
        }
        sql.append(" ORDER BY ev.started_at DESC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(sql.toString(), (rs, i) -> {
            Instant started = HealthRepository.instant(rs, "started_at");
            Instant ended = HealthRepository.instant(rs, "ended_at");
            return new HealthEventView(rs.getLong("id"), rs.getLong("sensor_id"), rs.getString("device_id"),
                    rs.getString("issue"), rs.getString("severity"), started, ended,
                    Duration.between(started, ended == null ? now : ended).toSeconds(), parseMap(rs.getString("details")));
        });
    }

    public List<ReadingPoint> recentReadings(long sensorId, Instant since) {
        return jdbc.query("""
                SELECT measured_at, received_at, seq_no, temperature_c, humidity_pct FROM readings
                WHERE sensor_id = ? AND received_at > ? ORDER BY received_at LIMIT 2000
                """, (rs, i) -> new ReadingPoint(HealthRepository.instant(rs, "measured_at"),
                HealthRepository.instant(rs, "received_at"), rs.getLong("seq_no"), rs.getDouble("temperature_c"),
                rs.getDouble("humidity_pct")), sensorId, Timestamp.from(since));
    }

    public List<ServiceView> services(Instant now, Duration staleAfter) {
        return jdbc.query("SELECT * FROM service_heartbeats ORDER BY service", (rs, i) -> {
            Instant lastSuccess = HealthRepository.instant(rs, "last_success_at");
            Instant lastError = HealthRepository.instant(rs, "last_error_at");
            String state;
            if (lastSuccess == null || Duration.between(lastSuccess, now).compareTo(staleAfter) > 0) {
                state = "DOWN";
            } else if (lastError != null && lastError.isAfter(lastSuccess)) {
                state = "FAILING";
            } else {
                state = "UP";
            }
            return new ServiceView(rs.getString("service"), state, HealthRepository.instant(rs, "last_run_at"),
                    lastSuccess, rs.getString("last_error"), lastError, rs.getLong("run_count"),
                    rs.getLong("error_count"));
        });
    }

    private SensorHealthView mapView(ResultSet rs, int row) throws SQLException {
        return new SensorHealthView(rs.getLong("id"), rs.getString("device_id"), rs.getString("kind"),
                rs.getString("model"), rs.getLong("expected_interval_s"), (Long) rs.getObject("equipment_id", Long.class),
                rs.getString("equipment_name"), rs.getString("location_name"), rs.getString("status"),
                rs.getString("connectivity"), rs.getInt("health_score"), rs.getBoolean("trusted"),
                parseList(rs.getString("issues")), HealthRepository.instant(rs, "last_seen_at"),
                HealthRepository.instant(rs, "last_measured_at"), (Long) rs.getObject("last_seq_no", Long.class),
                (Integer) rs.getObject("window_readings", Integer.class),
                rs.getObject("window_missing_pct") == null ? null : rs.getDouble("window_missing_pct"),
                (Integer) rs.getObject("signature_failures", Integer.class),
                HealthRepository.instant(rs, "status_since"), HealthRepository.instant(rs, "evaluated_at"));
    }

    private List<String> parseList(String s) {
        if (s == null) {
            return List.of();
        }
        try {
            return json.readValue(s, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String s) {
        if (s == null) {
            return Map.of();
        }
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}

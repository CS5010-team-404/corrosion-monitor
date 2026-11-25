package com.team404.corrosion.analytics.signal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * alert_signals in MySQL, with the same row format the platform's sensor-health signals use (docs/CONTRACTS.md), so
 * the alert service consumes both the same way. Times go in as a UTC LocalDateTime.
 */
@Repository
public class JdbcSignalStore implements SignalStore {

    static final String SOURCE = "ANALYTICS";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcSignalStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Map<String, LastSignal> lastSignals() {
        Map<String, LastSignal> last = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.dedup_key, s.state, s.severity
                FROM alert_signals s
                JOIN (SELECT dedup_key, MAX(id) AS id FROM alert_signals WHERE source = 'ANALYTICS' GROUP BY dedup_key) latest
                  ON latest.id = s.id
                """, (RowCallbackHandler) rs -> last.put(rs.getString("dedup_key"),
                new LastSignal(rs.getString("state"), rs.getString("severity"))));
        return last;
    }

    @Override
    public void append(NewSignal s) {
        jdbc.update("""
                INSERT INTO alert_signals
                  (source, signal_type, severity, state, equipment_id, sensor_id, dedup_key, title, message, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, SOURCE, s.type(), s.severity(), s.state(), s.equipmentId(), s.sensorId(), s.dedupKey(), s.title(),
                s.message(), toJson(s.payload()), LocalDateTime.ofInstant(s.createdAt(), ZoneOffset.UTC));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("signal payload is not serialisable", e);
        }
    }
}

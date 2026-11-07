package edu.neu.ecp.common.signal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSignalPublisher implements SignalPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcSignalPublisher(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void publish(AlertSignal s) {
        jdbc.update("""
                INSERT INTO alert_signals
                  (source, signal_type, severity, state, equipment_id, sensor_id, dedup_key, title, message, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                s.source().name(), s.type(), s.severity().name(), s.state().name(), s.equipmentId(), s.sensorId(),
                s.dedupKey(), s.title(), s.message(), toJson(s), Timestamp.from(clock.instant()));
    }

    private String toJson(AlertSignal s) {
        try {
            return json.writeValueAsString(s.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("signal payload is not serialisable", e);
        }
    }
}

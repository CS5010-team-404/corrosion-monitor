package edu.neu.ecp.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes to the outbox only. Hashing and chaining happen in one place (the audit service) to avoid races. */
@Component
public class JdbcAuditRecorder implements AuditRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcAuditRecorder(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void record(AuditEvent e) {
        jdbc.update("""
                INSERT INTO audit_outbox (occurred_at, actor_type, actor, action, entity_type, entity_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                Timestamp.from(clock.instant()), e.actorType().name(), e.actor(), e.action(), e.entityType(),
                e.entityId(), toJson(e));
    }

    private String toJson(AuditEvent e) {
        try {
            return json.writeValueAsString(e.details());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("audit details are not serialisable", ex);
        }
    }
}

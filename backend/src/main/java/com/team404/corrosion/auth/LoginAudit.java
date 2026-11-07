package com.team404.corrosion.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sign-in events for the audit log. Writes audit_outbox only (docs/CONTRACTS.md); the audit service chains them into
 * audit_log. Details never contain passwords or tokens: callers pass reasons, counts and times only.
 */
@Component
public class LoginAudit {

    public static final String USER_LOGIN = "USER_LOGIN";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    /** audit_outbox.actor is VARCHAR(64); a longer email typed at the login form must not fail the request. */
    private static final int ACTOR_MAX = 64;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public LoginAudit(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void record(String action, String email, Long userId, Map<String, ?> details) {
        String actor = email.length() > ACTOR_MAX ? email.substring(0, ACTOR_MAX) : email;
        jdbc.update("""
                INSERT INTO audit_outbox (occurred_at, actor_type, actor, action, entity_type, entity_id, details)
                VALUES (?, 'USER', ?, ?, 'user', ?, ?)
                """, Timestamp.from(clock.instant()), actor, action, userId == null ? null : userId.toString(),
                toJson(details));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit details are not serialisable", e);
        }
    }
}

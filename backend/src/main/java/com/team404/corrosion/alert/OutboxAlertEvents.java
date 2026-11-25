package com.team404.corrosion.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes audit_outbox only (docs/CONTRACTS.md; the audit service chains it) and publishes {@code {alertId, action}}
 * on /topic/alerts (when an AlertBroadcaster exists) after the surrounding transaction commits, so a page that refetches sees the change. A failed
 * push is logged and never undoes the change.
 */
@Component
public class OutboxAlertEvents implements AlertEvents {

    private static final Logger log = LoggerFactory.getLogger(OutboxAlertEvents.class);

    /** audit_outbox.actor is VARCHAR(64). */
    private static final int ACTOR_MAX = 64;
    public static final String SYSTEM_ACTOR = "alerts";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<AlertBroadcaster> broadcaster;

    public OutboxAlertEvents(JdbcTemplate jdbc, ObjectMapper json, Clock clock,
                             ObjectProvider<AlertBroadcaster> broadcaster) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.broadcaster = broadcaster;
    }

    @Override
    public void record(String action, long alertId, String actor, Map<String, Object> details) {
        boolean user = actor != null;
        String who = !user ? SYSTEM_ACTOR : actor.length() > ACTOR_MAX ? actor.substring(0, ACTOR_MAX) : actor;
        jdbc.update("""
                INSERT INTO audit_outbox (occurred_at, actor_type, actor, action, entity_type, entity_id, details)
                VALUES (?, ?, ?, ?, 'alert', ?, ?)
                """, Timestamp.from(clock.instant()), user ? "USER" : "SYSTEM", who, action, Long.toString(alertId),
                toJson(details));
        pushAfterCommit(alertId, action);
    }

    @Override
    public void updated(long alertId) {
        pushAfterCommit(alertId, UPDATED);
    }

    private void pushAfterCommit(long alertId, String action) {
        Map<String, Object> notice = Map.of("alertId", alertId, "action", action);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push(notice);
                }
            });
        } else {
            push(notice);
        }
    }

    private void push(Map<String, Object> notice) {
        try {
            broadcaster.ifAvailable(b -> b.publish(AlertBroadcaster.TOPIC, notice));
        } catch (RuntimeException e) {
            log.debug("alert push failed", e);
        }
    }

    private String toJson(Map<String, Object> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit details are not serialisable", e);
        }
    }
}

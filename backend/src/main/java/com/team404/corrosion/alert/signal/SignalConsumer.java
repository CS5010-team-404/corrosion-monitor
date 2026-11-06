package com.team404.corrosion.alert.signal;

import com.team404.corrosion.alert.AlertEvents;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads alert_signals in id order and sets processed_at (docs/CONTRACTS.md). Each signal and its processed_at update
 * share a transaction, so a crash never loses or repeats one. A failure that retrying cannot fix (a signal for
 * equipment that does not exist, an unknown state) is logged and the signal is marked processed, so it cannot block
 * the ones behind it. Any other failure stops the batch and the next poll tries the same signal again.
 */
@Component
public class SignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SignalProcessor processor;
    private final Clock clock;
    private final int batchSize;

    public SignalConsumer(JdbcTemplate jdbc, TransactionTemplate tx, AlertStore store, AlertEvents events, Clock clock,
                          @Value("${ecp.alerts.quiet-period:PT10M}") Duration quietPeriod,
                          @Value("${ecp.alerts.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.processor = new SignalProcessor(store, events, clock, quietPeriod);
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ecp.alerts.poll-interval:PT5S}")
    public void poll() {
        try {
            int handled;
            do {
                handled = consumeBatch();
            } while (handled == batchSize);
            int resolved = tx.execute(status -> processor.resolveQuiet());
            if (resolved > 0) {
                log.info("Auto-resolved {} alert(s) after the quiet period", resolved);
            }
        } catch (RuntimeException e) {
            log.warn("Alert signal poll failed, will retry: {}", e.toString());
        }
    }

    /** Returns how many signals were taken off the queue, including ones that were skipped. */
    int consumeBatch() {
        List<Signal> batch = jdbc.query("""
                SELECT id, source, signal_type, severity, state, equipment_id, sensor_id, dedup_key, title, message,
                       created_at, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.deviceId')) AS device_id
                FROM alert_signals WHERE processed_at IS NULL ORDER BY id LIMIT ?
                """, (rs, row) -> new Signal(rs.getLong("id"), rs.getString("source"), rs.getString("signal_type"),
                rs.getString("severity"), rs.getString("state"), (Long) rs.getObject("equipment_id"),
                (Long) rs.getObject("sensor_id"), rs.getString("device_id"), rs.getString("dedup_key"), rs.getString("title"),
                rs.getString("message"), rs.getTimestamp("created_at").toInstant()), batchSize);
        int done = 0;
        for (Signal signal : batch) {
            try {
                tx.executeWithoutResult(status -> {
                    processor.apply(signal);
                    markProcessed(signal.id());
                });
            } catch (DuplicateKeyException e) {
                throw e; // another instance opened this alert first; the next poll will find it and update it
            } catch (DataIntegrityViolationException | IllegalArgumentException e) {
                log.warn("Skipping alert signal {} ({}): {}", signal.id(), signal.dedupKey(), e.getMessage());
                tx.executeWithoutResult(status -> markProcessed(signal.id()));
            }
            done++;
        }
        return done;
    }

    private void markProcessed(long signalId) {
        jdbc.update("UPDATE alert_signals SET processed_at = ? WHERE id = ?", Timestamp.from(clock.instant()), signalId);
    }
}

package edu.neu.ecp.common.ops;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One row per background service in {@code service_heartbeats}; the System panel reads it. */
@Component
public class JdbcHeartbeats implements Heartbeats {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcHeartbeats(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void success(String service) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO service_heartbeats (service, last_run_at, last_success_at, run_count, error_count)
                VALUES (?, ?, ?, 1, 0)
                ON DUPLICATE KEY UPDATE last_run_at = VALUES(last_run_at), last_success_at = VALUES(last_success_at),
                                        run_count = run_count + 1
                """, service, now, now);
    }

    @Override
    public void failure(String service, Throwable error) {
        Timestamp now = Timestamp.from(clock.instant());
        String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbc.update("""
                INSERT INTO service_heartbeats (service, last_run_at, last_error, last_error_at, run_count, error_count)
                VALUES (?, ?, ?, ?, 1, 1)
                ON DUPLICATE KEY UPDATE last_run_at = VALUES(last_run_at), last_error = VALUES(last_error),
                                        last_error_at = VALUES(last_error_at), run_count = run_count + 1,
                                        error_count = error_count + 1
                """, service, now, message, now);
    }
}

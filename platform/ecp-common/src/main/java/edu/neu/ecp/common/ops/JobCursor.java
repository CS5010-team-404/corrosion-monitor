package edu.neu.ecp.common.ops;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Remembers "last processed id" per job in {@code job_cursors}, so polling jobs resume correctly after restarts. */
@Component
public class JobCursor {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JobCursor(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long get(String jobName) {
        List<Long> rows = jdbc.queryForList("SELECT last_id FROM job_cursors WHERE job_name = ?", Long.class, jobName);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    public void set(String jobName, long lastId) {
        jdbc.update("""
                INSERT INTO job_cursors (job_name, last_id, updated_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE last_id = VALUES(last_id), updated_at = VALUES(updated_at)
                """, jobName, lastId, Timestamp.from(clock.instant()));
    }
}

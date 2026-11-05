package edu.neu.ecp.ingestion;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the free 1 GB database from filling up: raw readings older than {@code ecp.retention.raw-days} are deleted
 * in small chunks every night. Analytics keeps its own assessments; reports should use those (or rollups).
 *
 * <p>Not a GuardedJob on purpose: it runs once a day, so a heartbeat would look "down" to the watchdog.
 */
@Component
public class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int rawDays;

    public RetentionJob(JdbcTemplate jdbc, Clock clock, @Value("${ecp.retention.raw-days:7}") int rawDays) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rawDays = rawDays;
    }

    @Scheduled(cron = "${ecp.retention.cron:0 15 3 * * *}", zone = "UTC")
    public void purge() {
        try {
            Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(rawDays)));
            int total = 0;
            int deleted;
            do {
                deleted = jdbc.update("DELETE FROM readings WHERE received_at < ? LIMIT 5000", cutoff);
                total += deleted;
            } while (deleted > 0);
            jdbc.update("DELETE FROM ingest_rejections WHERE received_at < ? LIMIT 50000", cutoff);
            LOG.info("retention removed {} readings older than {} days", total, rawDays);
        } catch (RuntimeException e) {
            LOG.error("retention failed", e);
        }
    }
}

package edu.neu.ecp.platform.health;

import static org.assertj.core.api.Assertions.assertThat;

import edu.neu.ecp.health.domain.Connectivity;
import edu.neu.ecp.health.domain.HealthAssessment;
import edu.neu.ecp.health.domain.HealthStatus;
import edu.neu.ecp.health.infra.SensorHealthService;
import edu.neu.ecp.platform.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ecp.health.startup-grace (default PT3M): right after a platform restart, sensors that were silent only because the
 * platform was down are not flagged OFFLINE, and an OFFLINE episode that was open before the restart is neither
 * cleared nor re-raised. After the grace, the normal rules apply. Runs the real health service on real SQL with a
 * clock the test moves by hand.
 */
class StartupGraceIntegrationTest extends MySqlIntegrationTest {

    /** A clock that only moves when the test says so. The health service reads its start time from it. */
    static final class ManualClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ManualClock manualClock() {
            return new ManualClock();
        }
    }

    @Autowired
    ManualClock clock;

    @Autowired
    SensorHealthService health;

    @Test
    void noOfflineDuringTheGraceThenNormalRules() {
        Instant start = clock.instant(); // the service started at this instant
        // Healthy until the platform went down 10 minutes ago.
        long silent = sensor("GRACE-SILENT", true, 15, start.minus(Duration.ofDays(1)));
        readings(silent, start.minus(Duration.ofMinutes(25)), start.minus(Duration.ofMinutes(10)));
        // Already offline (episode open) for 19 minutes before the restart.
        long dead = sensor("GRACE-DEAD", true, 15, start.minus(Duration.ofDays(1)));
        readings(dead, start.minus(Duration.ofMinutes(30)), start.minus(Duration.ofMinutes(20)));
        health(dead, "OFFLINE", false, start.minus(Duration.ofMinutes(19)));
        jdbc.update("""
                INSERT INTO sensor_health_events (sensor_id, issue, severity, started_at, details)
                VALUES (?, 'OFFLINE', 'CRITICAL', ?, JSON_OBJECT())
                """, dead, Timestamp.from(start.minus(Duration.ofMinutes(19))));

        // 1 minute after the restart: inside the grace
        clock.advance(Duration.ofMinutes(1));
        List<HealthAssessment> during = health.evaluateAll();

        HealthAssessment s = find(during, silent);
        assertThat(s.connectivity()).isEqualTo(Connectivity.LATE);
        assertThat(s.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(s.trusted()).isTrue();
        assertThat(storedStatus(silent)).isEqualTo("DEGRADED");
        assertThat(signals(silent, "RAISED")).isZero();

        HealthAssessment d = find(during, dead);
        assertThat(d.status()).isEqualTo(HealthStatus.OFFLINE); // held, not cleared by the grace
        assertThat(signals(dead, "CLEARED")).isZero();
        assertThat(openOfflineEpisodes(dead)).isEqualTo(1);

        // 3 minutes and 1 second after the restart: the grace is over
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        List<HealthAssessment> after = health.evaluateAll();

        HealthAssessment s2 = find(after, silent);
        assertThat(s2.status()).isEqualTo(HealthStatus.OFFLINE);
        assertThat(s2.connectivity()).isEqualTo(Connectivity.OFFLINE);
        assertThat(signals(silent, "RAISED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT dedup_key FROM alert_signals WHERE sensor_id = ?", String.class, silent))
                .isEqualTo("HEALTH:SENSOR_OFFLINE:" + silent);

        assertThat(find(after, dead).status()).isEqualTo(HealthStatus.OFFLINE);
        assertThat(signals(dead, "RAISED") + signals(dead, "CLEARED")).isZero(); // no flap across the restart
        assertThat(openOfflineEpisodes(dead)).isEqualTo(1);
    }

    private static HealthAssessment find(List<HealthAssessment> all, long sensorId) {
        return all.stream().filter(a -> a.sensorId() == sensorId).findFirst().orElseThrow();
    }

    private String storedStatus(long sensorId) {
        return jdbc.queryForObject("SELECT status FROM sensor_health WHERE sensor_id = ?", String.class, sensorId);
    }

    private int signals(long sensorId, String state) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM alert_signals WHERE sensor_id = ? AND signal_type = 'SENSOR_OFFLINE' AND state = ?
                """, Integer.class, sensorId, state);
    }

    private int openOfflineEpisodes(long sensorId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sensor_health_events WHERE sensor_id = ? AND issue = 'OFFLINE' AND ended_at IS NULL
                """, Integer.class, sensorId);
    }
}

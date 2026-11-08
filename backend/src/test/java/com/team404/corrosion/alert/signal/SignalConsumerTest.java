package com.team404.corrosion.alert.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.alert.AlertBroadcaster;
import com.team404.corrosion.alert.OutboxAlertEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V4 and the consumer against MySQL with the production settings (no Spring context). */
@Testcontainers
class SignalConsumerTest {

    private static final Duration QUIET = Duration.ofMinutes(10);

    @Container
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private Instant now;
    private SignalConsumer consumer;
    private final List<Object> pushed = new ArrayList<>();

    @BeforeAll
    static void migrate() {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName()
                + "?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        var dataSource = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM corrective_action");
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM alert_signals");
        jdbc.update("DELETE FROM audit_outbox");
        pushed.clear();
        now = Instant.parse("2026-11-06T10:00:00Z");
        Clock clock = new Clock() {
            @Override public Instant instant() { return now; }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("broadcaster", (AlertBroadcaster) (topic, payload) -> pushed.add(topic + " " + payload));
        var events = new OutboxAlertEvents(jdbc, new ObjectMapper(), clock, beans.getBeanProvider(AlertBroadcaster.class));
        consumer = new SignalConsumer(jdbc, tx, new JdbcAlertStore(jdbc), events, clock, QUIET, 100);
    }

    @Test
    void raisedOpensAnAlertOnTheEquipmentAndItsLocation() {
        long equipmentId = anEquipmentId();
        signal("ANALYTICS", "CONDENSATION_RISK", "CRITICAL", "RAISED", equipmentId, "ANALYTICS:CONDENSATION_RISK:" + equipmentId);

        assertThat(consumer.consumeBatch()).isEqualTo(1);

        Map<String, Object> alert = jdbc.queryForMap("SELECT * FROM alert");
        assertThat(alert.get("status")).isEqualTo("OPEN");
        assertThat(alert.get("severity")).isEqualTo("CRITICAL");
        assertThat(alert.get("equipment_id")).isEqualTo(equipmentId);
        assertThat(alert.get("location_id")).isEqualTo(
                jdbc.queryForObject("SELECT location_id FROM equipment WHERE id = ?", Long.class, equipmentId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_signals WHERE processed_at IS NULL", Long.class)).isZero();
    }

    @Test
    void repeatedRaisedSignalsKeepOneAlert() {
        long equipmentId = anEquipmentId();
        String key = "ANALYTICS:CONDENSATION_RISK:" + equipmentId;
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "CRITICAL", "RAISED", equipmentId, key);

        consumer.consumeBatch();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT severity FROM alert", String.class)).isEqualTo("CRITICAL");
    }

    @Test
    void clearedWaitsForTheQuietPeriodThenResolves() {
        long equipmentId = anEquipmentId();
        String key = "ANALYTICS:CONDENSATION_RISK:" + equipmentId;
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "CLEARED", equipmentId, key);
        consumer.poll();

        assertThat(jdbc.queryForObject("SELECT status FROM alert", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT condition_cleared_at FROM alert", Object.class)).isNotNull();

        now = now.plus(QUIET).plusSeconds(1);
        consumer.poll();

        Map<String, Object> alert = jdbc.queryForMap("SELECT status, resolved_at, resolved_by FROM alert");
        assertThat(alert.get("status")).isEqualTo("RESOLVED");
        assertThat(alert.get("resolved_at")).isNotNull();
        assertThat(alert.get("resolved_by")).isNull();
    }

    @Test
    void raisedAfterAClearedCancelsTheAutoResolve() {
        long equipmentId = anEquipmentId();
        String key = "ANALYTICS:CONDENSATION_RISK:" + equipmentId;
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "CLEARED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        now = now.plus(QUIET).plusSeconds(1);

        consumer.poll();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM alert", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT condition_cleared_at FROM alert", Object.class)).isNull();
    }

    @Test
    void aResolvedAlertDoesNotBlockANewOneWithTheSameKey() {
        long equipmentId = anEquipmentId();
        String key = "ANALYTICS:CONDENSATION_RISK:" + equipmentId;
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "CLEARED", equipmentId, key);
        consumer.poll();
        now = now.plus(QUIET).plusSeconds(1);
        consumer.poll();

        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        consumer.poll();

        assertThat(jdbc.queryForList("SELECT status FROM alert ORDER BY id", String.class))
                .containsExactly("RESOLVED", "OPEN");
    }

    @Test
    void openingAndAutoResolvingWriteAuditEventsAndPushNoticesOnlyAfterCommit() {
        long equipmentId = anEquipmentId();
        String key = "ANALYTICS:CONDENSATION_RISK:" + equipmentId;
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "CLEARED", equipmentId, key);
        consumer.poll();
        now = now.plus(QUIET).plusSeconds(1);
        consumer.poll();

        // opened, refreshed, cleared, auto-resolved: four notices, two of them audited
        long alertId = jdbc.queryForObject("SELECT id FROM alert", Long.class);
        assertThat(jdbc.queryForList("SELECT action FROM audit_outbox ORDER BY id", String.class))
                .containsExactly("ALERT_OPENED", "ALERT_RESOLVED");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM audit_outbox ORDER BY id LIMIT 1");
        assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(row.get("entity_type")).isEqualTo("alert");
        assertThat(row.get("entity_id")).isEqualTo(Long.toString(alertId));
        assertThat(pushed).hasSize(4).allMatch(p -> p.toString().startsWith("/topic/alerts "));
    }

    @Test
    void aSkippedSignalWritesNoAuditEventAndPushesNothing() {
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", 999_999L, "ANALYTICS:CONDENSATION_RISK:999999");

        consumer.consumeBatch();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox", Long.class)).isZero();
        assertThat(pushed).isEmpty();
    }

    @Test
    void aSystemSignalWithoutEquipmentOpensAnAlertWithoutALocation() {
        signal("SYSTEM", "SERVICE_DOWN", "CRITICAL", "RAISED", null, "SYSTEM:SERVICE_DOWN:ingestion");

        consumer.consumeBatch();

        assertThat(jdbc.queryForObject("SELECT location_id FROM alert", Object.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT equipment_id FROM alert", Object.class)).isNull();
    }

    @Test
    void aSignalForUnknownEquipmentIsSkippedAndDoesNotBlockTheNext() {
        long equipmentId = anEquipmentId();
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", 999_999L, "ANALYTICS:CONDENSATION_RISK:999999");
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, "ANALYTICS:CONDENSATION_RISK:" + equipmentId);

        assertThat(consumer.consumeBatch()).isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT dedup_key FROM alert", String.class))
                .containsExactly("ANALYTICS:CONDENSATION_RISK:" + equipmentId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_signals WHERE processed_at IS NULL", Long.class)).isZero();
    }

    @Test
    void signalsAreConsumedInIdOrder() {
        long equipmentId = anEquipmentId();
        String key = "ANALYTICS:CONDENSATION_RISK:" + equipmentId;
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "RAISED", equipmentId, key);
        signal("ANALYTICS", "CONDENSATION_RISK", "WARNING", "CLEARED", equipmentId, key);

        consumer.consumeBatch();

        List<Long> ids = jdbc.queryForList("SELECT id FROM alert_signals ORDER BY processed_at, id", Long.class);
        assertThat(ids).isSorted();
    }

    private static long anEquipmentId() {
        return jdbc.queryForObject("SELECT MIN(id) FROM equipment", Long.class);
    }

    private void signal(String source, String type, String severity, String state, Long equipmentId, String key) {
        jdbc.update("""
                INSERT INTO alert_signals (source, signal_type, severity, state, equipment_id, dedup_key, title, message,
                                           payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', ?)
                """, source, type, severity, state, equipmentId, key, type, type + " " + state,
                java.sql.Timestamp.from(now));
    }
}

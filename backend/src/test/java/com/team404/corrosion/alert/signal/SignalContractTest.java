package com.team404.corrosion.alert.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.alert.AlertBroadcaster;
import com.team404.corrosion.alert.OutboxAlertEvents;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Contract with the data platform: fixtures/alert-signals.json holds the exact alert_signals rows the platform's sensor
 * health writes when SIM-010 goes offline and recovers (generated and checked by the platform's SignalContractTest in
 * ecp-health). This test feeds them to the real SignalConsumer on MySQL and checks the alert that comes out, so a
 * change on either side that breaks the other fails a test. Same approach as the JWT fixture (TokenFixtureTest).
 */
@Testcontainers
class SignalContractTest {

    private static final Path PLATFORM_COPY = Path.of("../platform/ecp-health/src/test/resources/fixtures/alert-signals.json");
    private static final String COLUMNS = "source, signal_type, severity, state, equipment_id, sensor_id, dedup_key, title, message, payload, created_at";

    @Container
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static JsonNode rows;
    private Instant now;

    @BeforeAll
    static void setUp() throws IOException {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL.getDatabaseName()
                + "?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        var dataSource = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (InputStream in = SignalContractTest.class.getResourceAsStream("/fixtures/alert-signals.json")) {
            rows = new ObjectMapper().readTree(in);
        }
    }

    private SignalConsumer consumer() {
        Clock clock = new Clock() {
            @Override public Instant instant() { return now; }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("broadcaster", (AlertBroadcaster) (topic, payload) -> { });
        var events = new OutboxAlertEvents(jdbc, new ObjectMapper(), clock, beans.getBeanProvider(AlertBroadcaster.class));
        return new SignalConsumer(jdbc, tx, new JdbcAlertStore(jdbc), events, clock, Duration.ofMinutes(10), 100);
    }

    /** Inserts one fixture row exactly as the platform's JdbcSignalPublisher does. */
    private static void insert(JsonNode r) {
        jdbc.update("INSERT INTO alert_signals (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.get("source").asText(), r.get("signal_type").asText(), r.get("severity").asText(), r.get("state").asText(),
                r.get("equipment_id").asLong(), r.get("sensor_id").asLong(), r.get("dedup_key").asText(), r.get("title").asText(),
                r.get("message").asText(), r.get("payload").toString(), Timestamp.from(Instant.parse(r.get("created_at").asText())));
    }

    @Test
    void theOfflineSignalsFromSensorHealthOpenAndClearOneAlert() {
        JsonNode raised = rows.get(0), cleared = rows.get(1);
        assertThat(raised.get("state").asText()).isEqualTo("RAISED");
        assertThat(cleared.get("state").asText()).isEqualTo("CLEARED");

        now = Instant.parse("2026-11-20T12:00:05Z");
        insert(raised);
        assertThat(consumer().consumeBatch()).isEqualTo(1);
        Map<String, Object> alert = jdbc.queryForMap("SELECT * FROM alert WHERE dedup_key = 'HEALTH:SENSOR_OFFLINE:10'");
        assertThat(alert.get("status")).isEqualTo("OPEN");
        assertThat(alert.get("severity")).isEqualTo("CRITICAL");
        assertThat(alert.get("source")).isEqualTo("SENSOR_HEALTH");
        assertThat(alert.get("type")).isEqualTo("SENSOR_OFFLINE");
        assertThat(alert.get("title")).isEqualTo("Sensor offline: SIM-010");
        assertThat(alert.get("message")).isEqualTo("SIM-010: No readings received for 2 min 0 s.");
        assertThat(alert.get("equipment_id")).isEqualTo(10L);
        assertThat(alert.get("sensor_id")).isEqualTo(10L);
        assertThat(alert.get("device_id")).isEqualTo("SIM-010");   // read from the JSON payload
        assertThat(alert.get("location_id")).isEqualTo(jdbc.queryForObject("SELECT location_id FROM equipment WHERE id = 10", Long.class));
        assertThat(alert.get("condition_cleared_at")).isNull();

        now = Instant.parse("2026-11-20T12:05:05Z");
        insert(cleared);
        assertThat(consumer().consumeBatch()).isEqualTo(1);
        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM alert WHERE dedup_key = 'HEALTH:SENSOR_OFFLINE:10'");
        assertThat(after.get("id")).isEqualTo(alert.get("id"));                         // the same alert, not a new one
        assertThat(after.get("status")).isEqualTo("OPEN");                             // resolves after the quiet period
        assertThat(after.get("condition_cleared_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_signals WHERE processed_at IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox WHERE action = 'ALERT_OPENED'", Long.class)).isEqualTo(1);
    }

    @Test
    void thePlatformHasTheSameFixture() throws IOException {
        byte[] ours;
        try (InputStream in = SignalContractTest.class.getResourceAsStream("/fixtures/alert-signals.json")) {
            ours = in.readAllBytes();
        }
        assertThat(Files.readAllBytes(PLATFORM_COPY)).isEqualTo(ours);
    }
}

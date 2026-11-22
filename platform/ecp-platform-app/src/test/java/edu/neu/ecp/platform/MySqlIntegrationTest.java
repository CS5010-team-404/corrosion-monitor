package edu.neu.ecp.platform;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base for platform integration tests: the whole Spring context, Flyway migrations and real SQL on MySQL 8.4 with the
 * production settings from docs/DEPLOYMENT.md (ANSI sql_mode, strict, required primary keys), over a UTC session like
 * the production URL. One container serves every test class; each test makes its own uniquely named sensors.
 *
 * <p>Background jobs are off ({@code ecp.scheduling.enabled=false}, forecast publishing, simulator, demo seed), so
 * tests run each job by hand and nothing changes behind their back.
 */
@SpringBootTest(properties = {
        "ecp.scheduling.enabled=false",
        "ecp.forecast.publish.enabled=false",
        "ecp.simulator.enabled=false",
        "ecp.demo.seed=false",
        "ecp.platform.auth=jwt",
        "ecp.security.jwt-secret=" + MySqlIntegrationTest.JWT_SECRET})
public abstract class MySqlIntegrationTest {

    /** Obviously fake; never use it in configuration. */
    public static final String JWT_SECRET = "test-only-platform-jwt-secret-never-in-production-01";

    static final String SQL_MODE = "REAL_AS_FLOAT,PIPES_AS_CONCAT,ANSI_QUOTES,IGNORE_SPACE,ONLY_FULL_GROUP_BY,"
            + "ANSI,STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("corrosion_monitor")
            .withCommand("--sql-mode=" + SQL_MODE, "--sql-require-primary-key=ON")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false");

    static {
        MYSQL.start(); // stopped by Testcontainers when the JVM exits
    }

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /** Registers a sensor with a unique device id and returns its id. */
    protected long sensor(String prefix, boolean active, int intervalS, Instant createdAt) {
        String deviceId = prefix + "-" + UNIQUE.incrementAndGet();
        jdbc.update("""
                INSERT INTO sensors (device_id, equipment_id, equipment_name, location_name, kind, model, secret_enc,
                                     expected_interval_s, active, sim_surface_offset_c, created_at)
                VALUES (?, 1, 'Rack test', 'Test room', 'SIMULATED', 'SIM-SHT31', 'not-used', ?, ?, 1.5, ?)
                """, deviceId, intervalS, active, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM sensors WHERE device_id = ?", Long.class, deviceId);
    }

    protected String deviceId(long sensorId) {
        return jdbc.queryForObject("SELECT device_id FROM sensors WHERE id = ?", String.class, sensorId);
    }

    /**
     * One reading every 15 s from {@code from} until {@code to}, with the room slowly getting more humid so the
     * condensation margin falls.
     */
    protected void readings(long sensorId, Instant from, Instant to) {
        long seq = 1;
        for (Instant t = from; !t.isAfter(to); t = t.plus(Duration.ofSeconds(15)), seq++) {
            double minutes = Duration.between(from, t).toSeconds() / 60.0;
            jdbc.update("""
                    INSERT INTO readings (sensor_id, seq_no, measured_at, received_at, temperature_c, humidity_pct)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, sensorId, seq, Timestamp.from(t), Timestamp.from(t.plusMillis(150)),
                    22.0 + 0.01 * Math.sin(seq), 55.0 + 0.4 * minutes);
        }
    }

    /** Writes a sensor_health row as the health job would. */
    protected void health(long sensorId, String status, boolean trusted, Instant at) {
        jdbc.update("""
                INSERT INTO sensor_health (sensor_id, status, connectivity, trusted, health_score, issues, status_since,
                                           evaluated_at)
                VALUES (?, ?, 'ONLINE', ?, 100, JSON_ARRAY(), ?, ?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), trusted = VALUES(trusted)
                """, sensorId, status, trusted, Timestamp.from(at), Timestamp.from(at));
    }
}

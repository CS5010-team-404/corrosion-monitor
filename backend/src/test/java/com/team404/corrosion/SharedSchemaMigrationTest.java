package com.team404.corrosion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The backend and the data platform share one database, each with its own Flyway history table
 * (flyway_schema_history and flyway_platform_history). Either service may start first, so both orders must give
 * the same schema. This test only reads the platform's migration file; it does not call the platform.
 */
@Testcontainers
class SharedSchemaMigrationTest {

    private static final Path PLATFORM_MIGRATIONS =
            Path.of("../platform/ecp-platform-app/src/main/resources/db/platform").toAbsolutePath().normalize();

    private static final List<String> EXPECTED_TABLES = List.of(
            // team schema (V1, V2)
            "app_user", "equipment_profile", "location", "equipment", "risk_state", "forecast", "alert",
            "corrective_action", "audit_log",
            // shared contract tables (both sides)
            "alert_signals", "audit_outbox",
            // platform
            "sensors", "readings", "ingest_rejections", "sensor_health", "sensor_health_events",
            "service_heartbeats", "job_cursors",
            // history
            "flyway_schema_history", "flyway_platform_history");

    // Binary logging is on in MySQL 8.0; without this flag a non-SUPER account cannot create the audit triggers.
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withCommand("--log-bin-trust-function-creators=1");

    @BeforeAll
    static void createDatabases() throws SQLException {
        assertThat(PLATFORM_MIGRATIONS.resolve("V1__platform_tables.sql")).exists();
        // Testcontainers sets the root password to the user's password. The services run as the ordinary user.
        try (Connection c = DriverManager.getConnection(jdbcUrl(""), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            for (String db : List.of("backend_first", "platform_first")) {
                s.execute("CREATE DATABASE " + db);
                s.execute("GRANT ALL ON " + db + ".* TO '" + MYSQL.getUsername() + "'@'%'");
            }
        }
    }

    @Test
    void backendThenPlatform() throws SQLException {
        String db = "backend_first";
        assertThat(backend(db).migrate().migrationsExecuted).isGreaterThanOrEqualTo(2);
        assertThat(platform(db).migrate().migrationsExecuted).isEqualTo(1);
        assertSharedSchema(db);
    }

    @Test
    void platformThenBackend() throws SQLException {
        String db = "platform_first";
        assertThat(platform(db).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(backend(db).migrate().migrationsExecuted).isGreaterThanOrEqualTo(2);
        assertSharedSchema(db);
    }

    private static void assertSharedSchema(String db) throws SQLException {
        assertThat(tables(db)).containsAll(EXPECTED_TABLES).doesNotContain("sensor", "reading", "used_nonce");
        assertThat(count(db, "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = '" + db
                + "' AND event_object_table = 'audit_log'")).isEqualTo(2);
        // Running either side again changes nothing.
        assertThat(backend(db).migrate().migrationsExecuted).isZero();
        assertThat(platform(db).migrate().migrationsExecuted).isZero();
        assertThat(backend(db).info().pending()).isEmpty();
    }

    /** Same settings as backend/src/main/resources/application.properties. */
    private static Flyway backend(String db) {
        return Flyway.configure()
                .dataSource(jdbcUrl(db), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0")
                .load();
    }

    /** Same settings as platform/ecp-platform-app/src/main/resources/application.yml. */
    private static Flyway platform(String db) {
        return Flyway.configure()
                .dataSource(jdbcUrl(db), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + PLATFORM_MIGRATIONS)
                .table("flyway_platform_history")
                .baselineOnMigrate(true).baselineVersion("0")
                .load();
    }

    private static String jdbcUrl(String db) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + db
                + "?connectionTimeZone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private static List<String> tables(String db) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl(db), MYSQL.getUsername(), MYSQL.getPassword());
             ResultSet rs = c.getMetaData().getTables(db, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static long count(String db, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(db), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

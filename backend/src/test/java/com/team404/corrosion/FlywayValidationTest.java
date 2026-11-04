package com.team404.corrosion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Starts the backend on a fresh MySQL 8.0: Flyway applies every migration and the context starts with
 * spring.jpa.hibernate.ddl-auto=validate, so a mismatch between entities and migrations fails here.
 */
@SpringBootTest(properties = "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001")
@Testcontainers
class FlywayValidationTest {

    // Binary logging is on in MySQL 8.0; without this flag the app account cannot create the audit triggers.
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withCommand("--log-bin-trust-function-creators=1");

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyMigrationIsApplied() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied())
                .filteredOn(m -> m.getVersion() != null && !"0".equals(m.getVersion().getVersion()))
                .extracting(MigrationInfo::getState)
                .isNotEmpty()
                .containsOnly(MigrationState.SUCCESS);
    }

    @Test
    void appUserAcceptsTheFourRolesOnly() {
        for (String role : new String[] {"TECHNICIAN", "OPERATOR", "MANAGER", "ADMIN"}) {
            jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES (?, ?, 'x', ?)",
                    role, role.toLowerCase() + "@roles.test", role);
        }
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (name, email, password_hash, role) VALUES ('A', 'auditor@roles.test', 'x', 'AUDITOR')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditLogIsAppendOnly() {
        jdbc.update("""
                INSERT INTO audit_log (ts, event_type, outcome, prev_hash, hash)
                VALUES (NOW(3), 'TEST', 'SUCCESS', REPEAT('0', 64), SHA2('append-only-test', 256))
                """);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET outcome = 'FAILURE'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void demoEquipmentMatchesThePlatformDemoDevices() {
        // Same order and names as platform/ecp-platform-app/.../demo/DemoDevices.java (SIM-001..SIM-012 -> ids 1..12).
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(e.id, ' ', e.name, ' @ ', l.name, ' / ', p.name)
                FROM equipment e JOIN location l ON l.id = e.location_id JOIN equipment_profile p ON p.id = e.profile_id
                ORDER BY e.id
                """, String.class)).containsExactly(
                "1 Rack A1 @ Server Room A / IT equipment (ASHRAE)",
                "2 Rack A2 @ Server Room A / IT equipment (ASHRAE)",
                "3 Rack A3 @ Server Room A / IT equipment (ASHRAE)",
                "4 Core switch cabinet @ Telecom Closet 2 / Telecom cabinet",
                "5 Patch panel C2 @ Telecom Closet 2 / Telecom cabinet",
                "6 Router cabinet @ Telecom Closet 2 / Telecom cabinet",
                "7 PLC panel L3-01 @ Factory Floor, Line 3 / Industrial control panel",
                "8 Drive panel L3-02 @ Factory Floor, Line 3 / Industrial control panel",
                "9 PLC panel L3-03 @ Factory Floor, Line 3 / Industrial control panel",
                "10 Kiosk N1 @ North Gate / Outdoor enclosure",
                "11 Kiosk N2 @ North Gate / Outdoor enclosure",
                "12 Barrier controller @ North Gate / Outdoor enclosure");
    }

    @Test
    void replacedTablesAreGoneAndEquipmentExists() {
        Integer gone = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN ('sensor', 'reading', 'used_nonce')
                """, Integer.class);
        Integer equipment = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'equipment'
                """, Integer.class);
        assertThat(gone).isZero();
        assertThat(equipment).isEqualTo(1);
    }
}

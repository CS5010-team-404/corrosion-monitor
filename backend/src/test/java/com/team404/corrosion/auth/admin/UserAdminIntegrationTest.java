package com.team404.corrosion.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.auth.AuthService;
import com.team404.corrosion.auth.LoginAudit;
import com.team404.corrosion.auth.Role;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * UserAdminService against MySQL 8.4 with the production settings (V1-V9 applied by Flyway): unlock and role change
 * write their audit_outbox rows, in the same transaction as the change, and sign-in records last_login_at.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.password=" + UserAdminIntegrationTest.PASSWORD,
        "ecp.audit.scheduler.enabled=false"})
@Testcontainers
class UserAdminIntegrationTest {

    static final String PASSWORD = "demo-password-for-tests";
    static final String ADMIN = "admin@corrosionwatch.app";
    static final String TECH = "tech@corrosionwatch.app";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    UserAdminService service;

    @Autowired
    AuthService auth;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoSpyBean
    LoginAudit audit;

    @BeforeEach
    void reset() {
        doCallRealMethod().when(audit).record(any(), any(), any(), anyMap());
        jdbc.update("UPDATE app_user SET role = 'TECHNICIAN', failed_logins = 0, locked_until = NULL WHERE email = ?",
                TECH);
        jdbc.update("DELETE FROM audit_outbox");
    }

    private long techId() {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, TECH);
    }

    private List<Map<String, Object>> outbox(String action) {
        return jdbc.queryForList("SELECT actor_type, actor, entity_type, entity_id, CAST(details AS CHAR) AS details "
                + "FROM audit_outbox WHERE action = ? ORDER BY id", action);
    }

    @Test
    void lockedAccountUnlockedByAnAdminWritesAccountUnlocked() throws Exception {
        for (int i = 0; i < 5; i++) {
            auth.login(TECH, "wrong-password");
        }
        assertThat(service.list()).filteredOn(u -> u.email().equals(TECH)).singleElement()
                .satisfies(u -> assertThat(u.lockedUntil()).isNotNull());

        UserAdminService.UserView view = service.unlock(techId(), ADMIN);

        assertThat(view.lockedUntil()).isNull();
        assertThat(jdbc.queryForMap("SELECT failed_logins, locked_until FROM app_user WHERE email = ?", TECH))
                .containsEntry("failed_logins", 0).containsEntry("locked_until", null);
        List<Map<String, Object>> rows = outbox(UserAdminService.ACCOUNT_UNLOCKED);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor_type", "USER").containsEntry("actor", ADMIN)
                    .containsEntry("entity_type", "user").containsEntry("entity_id", String.valueOf(techId()));
            JsonNode details = readJson(row);
            assertThat(details.get("user").asText()).isEqualTo(TECH);
            assertThat(details.get("failedLogins").asInt()).isEqualTo(5);
            assertThat(details.get("lockedUntil").asText()).isNotBlank();
        });
        // and the account works again
        assertThat(auth.login(TECH, PASSWORD)).isInstanceOf(AuthService.LoginResult.Success.class);
    }

    @Test
    void unlockingAnAccountThatIsNotLockedWritesNothing() {
        service.unlock(techId(), ADMIN);
        assertThat(outbox(UserAdminService.ACCOUNT_UNLOCKED)).isEmpty();
    }

    @Test
    void roleChangeWritesUserRoleChangedWithOldAndNewRole() throws Exception {
        service.changeRole(techId(), Role.MANAGER, ADMIN);

        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE email = ?", String.class, TECH))
                .isEqualTo("MANAGER");
        List<Map<String, Object>> rows = outbox(UserAdminService.USER_ROLE_CHANGED);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor", ADMIN).containsEntry("entity_id", String.valueOf(techId()));
            JsonNode details = readJson(row);
            assertThat(details.get("oldRole").asText()).isEqualTo("TECHNICIAN");
            assertThat(details.get("newRole").asText()).isEqualTo("MANAGER");
            assertThat(details.get("user").asText()).isEqualTo(TECH);
        });

        service.changeRole(techId(), Role.MANAGER, ADMIN);
        assertThat(outbox(UserAdminService.USER_ROLE_CHANGED)).as("no change, no second row").hasSize(1);
    }

    @Test
    void roleChangeIsRolledBackWhenItsAuditRowCannotBeWritten() {
        doThrow(new IllegalStateException("outbox down"))
                .when(audit).record(eq(UserAdminService.USER_ROLE_CHANGED), any(), any(), anyMap());

        assertThatThrownBy(() -> service.changeRole(techId(), Role.OPERATOR, ADMIN))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE email = ?", String.class, TECH))
                .isEqualTo("TECHNICIAN");
    }

    @Test
    void refusedRoleChangeWritesNothing() {
        assertThatThrownBy(() -> service.changeRole(
                jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, ADMIN), Role.OPERATOR, ADMIN))
                .isInstanceOf(UserAdminService.RoleChangeRefusedException.class);
        assertThat(outbox(UserAdminService.USER_ROLE_CHANGED)).isEmpty();
    }

    @Test
    void signInRecordsLastLoginAtInUtc() {
        assertThat(auth.login("operator@corrosionwatch.app", PASSWORD))
                .isInstanceOf(AuthService.LoginResult.Success.class);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_login_at, UTC_TIMESTAMP(3) AS now_utc FROM app_user WHERE email = ?",
                "operator@corrosionwatch.app");
        Duration age = Duration.between((LocalDateTime) row.get("last_login_at"), (LocalDateTime) row.get("now_utc"));
        assertThat(age).isBetween(Duration.ZERO, Duration.ofMinutes(1));
        assertThat(service.list()).filteredOn(u -> u.email().equals("operator@corrosionwatch.app")).singleElement()
                .satisfies(u -> assertThat(u.lastLoginAt()).isNotNull());
    }

    private JsonNode readJson(Map<String, Object> row) {
        try {
            return json.readTree((String) row.get("details"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

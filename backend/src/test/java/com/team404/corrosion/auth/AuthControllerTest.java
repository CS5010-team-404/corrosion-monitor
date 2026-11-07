package com.team404.corrosion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.team404.corrosion.AivenMySql;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The login endpoint end to end: HTTP, security, JPA and audit_outbox on MySQL with the production settings.
 * Each test uses a different demo user, so a lock in one test does not affect another.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.password=" + AuthControllerTest.PASSWORD,
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerTest {

    static final String PASSWORD = "demo-password-for-tests";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String tokenFor(String email) throws Exception {
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @Test
    void successReturnsTokenAndProfile() throws Exception {
        login("tech@corrosionwatch.app", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(startsWith("eyJ")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.email").value("tech@corrosionwatch.app"))
                .andExpect(jsonPath("$.fullName").value("Tariq Technician"))
                .andExpect(jsonPath("$.role").value("TECHNICIAN"));
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        login("  Manager@CorrosionWatch.APP ", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("manager@corrosionwatch.app"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        String wrong = login("operator@corrosionwatch.app", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value(AuthController.BAD_CREDENTIALS))
                .andReturn().getResponse().getContentAsString();
        String unknown = login("nobody@corrosionwatch.app", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        // identical apart from the timestamp
        ObjectNode u = (ObjectNode) json.readTree(unknown);
        ObjectNode w = (ObjectNode) json.readTree(wrong);
        u.remove("timestamp");
        w.remove("timestamp");
        assertThat(u).isEqualTo(w);
    }

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        String email = "admin@corrosionwatch.app";
        for (int i = 0; i < 4; i++) {
            login(email, "nope").andExpect(status().isUnauthorized());
        }
        login(email, "nope")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value(containsString("Try again after")))
                .andExpect(jsonPath("$.lockedUntil").isNotEmpty());
        login(email, PASSWORD).andExpect(status().isLocked());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT failed_logins, locked_until, UTC_TIMESTAMP(3) AS now_utc FROM app_user WHERE email = ?", email);
        assertThat(row.get("failed_logins")).isEqualTo(5);
        // stored in UTC: about 15 minutes after the database's UTC clock, whatever the JVM's time zone
        long minutes = java.time.Duration.between(((java.time.LocalDateTime) row.get("now_utc")),
                ((java.time.LocalDateTime) row.get("locked_until"))).toMinutes();
        assertThat(minutes).isBetween(14L, 15L);

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_outbox WHERE actor = ? ORDER BY id", String.class, email);
        assertThat(actions).containsSubsequence("USER_LOGIN_FAILED", "ACCOUNT_LOCKED", "USER_LOGIN_FAILED");
    }

    @Test
    void auditDetailsNeverContainPasswordsOrTokens() throws Exception {
        tokenFor("tech@corrosionwatch.app");
        login("tech@corrosionwatch.app", "a-wrong-password-123").andExpect(status().isUnauthorized());
        // No password or token anywhere in the outbox, whichever service wrote the row.
        List<String> allDetails = jdbc.queryForList(
                "SELECT CAST(details AS CHAR) FROM audit_outbox WHERE details IS NOT NULL", String.class);
        assertThat(allDetails).noneMatch(d -> d.contains(PASSWORD) || d.contains("a-wrong-password-123")
                || d.contains("eyJ"));
        // Login events only: the audit service also writes events (entity_type audit), which are allowed.
        String loginEvents = "action IN ('USER_LOGIN', 'USER_LOGIN_FAILED', 'ACCOUNT_LOCKED')";
        List<String> loginDetails = jdbc.queryForList(
                "SELECT CAST(details AS CHAR) FROM audit_outbox WHERE " + loginEvents, String.class);
        assertThat(loginDetails).isNotEmpty();
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_type FROM audit_outbox WHERE " + loginEvents, String.class))
                .containsExactly("USER");
        assertThat(jdbc.queryForList("SELECT DISTINCT entity_type FROM audit_outbox WHERE " + loginEvents, String.class))
                .containsExactly("user");
    }

    @Test
    void veryLongEmailIsAnOrdinaryFailureNotAServerError() throws Exception {
        login("x".repeat(300) + "@corrosionwatch.app", "whatever").andExpect(status().isUnauthorized());
    }

    @Test
    void missingFieldsAreBadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void meReturnsTheTokensUser() throws Exception {
        String token = tokenFor("manager@corrosionwatch.app");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("manager@corrosionwatch.app"))
                .andExpect(jsonPath("$.fullName").value("Morgan Manager"))
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void protectedPathsNeedAToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleRulesFollowTheContract() throws Exception {
        String tech = "Bearer " + tokenFor("tech@corrosionwatch.app");
        String manager = "Bearer " + tokenFor("manager@corrosionwatch.app");
        // admin paths: admins only
        mvc.perform(get("/api/v1/admin/users").header("Authorization", tech))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN"));
        // audit (docs/CONTRACTS.md): MANAGER and ADMIN 200; TECHNICIAN and OPERATOR 403; no token 401
        // the lock test above leaves this user locked; clear it so the order of tests does not matter
        jdbc.update("UPDATE app_user SET failed_logins = 0, locked_until = NULL WHERE email = ?",
                "admin@corrosionwatch.app");
        String admin = "Bearer " + tokenFor("admin@corrosionwatch.app");
        String operator = "Bearer " + tokenFor("operator@corrosionwatch.app");
        mvc.perform(get("/api/v1/audit").header("Authorization", manager)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit").header("Authorization", tech)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").header("Authorization", operator)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
        // acting on alerts: technicians and admins
        mvc.perform(post("/api/v1/alerts/1/acknowledge").header("Authorization", manager)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/alerts/1/acknowledge").header("Authorization", tech)).andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}

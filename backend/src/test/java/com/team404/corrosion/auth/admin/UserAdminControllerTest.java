package com.team404.corrosion.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.auth.Role;
import com.team404.corrosion.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Admin > Users over HTTP: role rules, the JSON shape and the 409 rules, on MySQL with the production settings.
 * Tokens are issued directly, so no test depends on sign-in (or is affected by a lockout).
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.password=demo-password-for-tests",
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class UserAdminControllerTest {

    static final String ADMIN = "admin@corrosionwatch.app";
    static final String SECOND_ADMIN = "second.admin@corrosionwatch.app";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TokenService tokens;

    @BeforeEach
    void demoUsersOnly() {
        jdbc.update("DELETE FROM app_user WHERE email NOT LIKE '%@corrosionwatch.app' OR email = ?", SECOND_ADMIN);
        jdbc.update("UPDATE app_user SET role = 'ADMIN', failed_logins = 0, locked_until = NULL WHERE email = ?", ADMIN);
        jdbc.update("UPDATE app_user SET role = 'TECHNICIAN' WHERE email = 'tech@corrosionwatch.app'");
    }

    private String bearer(String email, Role role) {
        return "Bearer " + tokens.issue(email, "Test " + role, role).value();
    }

    private ResultActions as(String auth, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(auth == null ? request : request.header("Authorization", auth));
    }

    private MockHttpServletRequestBuilder setRole(long id, String role) {
        return patch("/api/v1/admin/users/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}");
    }

    private long idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    @Test
    void adminGetsEveryUserInTheUserShape() throws Exception {
        String body = as(bearer(ADMIN, Role.ADMIN), get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].username").value(hasItems(ADMIN, "tech@corrosionwatch.app")))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].fullName").isString())
                .andExpect(jsonPath("$[0].email").isString())
                .andExpect(jsonPath("$[0].role").isString())
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].lockedUntil").isEmpty())
                .andExpect(jsonPath("$[0].lastLoginAt").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("hash")
                .doesNotContain("$2a$").doesNotContainIgnoringCase("failed");
    }

    @Test
    void onlyAdminsMayUseIt() throws Exception {
        long tech = idOf("tech@corrosionwatch.app");
        for (Role role : new Role[] {Role.TECHNICIAN, Role.OPERATOR, Role.MANAGER}) {
            String auth = bearer(role.name().toLowerCase() + "@corrosionwatch.app", role);
            as(auth, get("/api/v1/admin/users")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
            as(auth, post("/api/v1/admin/users/" + tech + "/unlock")).andExpect(status().isForbidden());
            as(auth, setRole(tech, "ADMIN")).andExpect(status().isForbidden());
        }
        as(null, get("/api/v1/admin/users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        as(null, post("/api/v1/admin/users/" + tech + "/unlock")).andExpect(status().isUnauthorized());
        as(null, setRole(tech, "ADMIN")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, tech))
                .isEqualTo("TECHNICIAN");
    }

    @Test
    void unlockClearsTheLockAndTheFailureCount() throws Exception {
        long tech = idOf("tech@corrosionwatch.app");
        jdbc.update("UPDATE app_user SET failed_logins = 5, locked_until = UTC_TIMESTAMP(3) + INTERVAL 15 MINUTE "
                + "WHERE id = ?", tech);
        as(bearer(ADMIN, Role.ADMIN), get("/api/v1/admin/users")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + tech + ")].lockedUntil").isNotEmpty());

        as(bearer(ADMIN, Role.ADMIN), post("/api/v1/admin/users/" + tech + "/unlock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tech))
                .andExpect(jsonPath("$.lockedUntil").isEmpty());

        assertThat(jdbc.queryForMap("SELECT failed_logins, locked_until FROM app_user WHERE id = ?", tech))
                .containsEntry("failed_logins", 0).containsEntry("locked_until", null);
    }

    @Test
    void roleChangeSaves() throws Exception {
        long tech = idOf("tech@corrosionwatch.app");
        as(bearer(ADMIN, Role.ADMIN), setRole(tech, "manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE id = ?", String.class, tech))
                .isEqualTo("MANAGER");
    }

    @Test
    void anAdminCannotDemoteThemselves() throws Exception {
        jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES ('Second Admin', ?, 'x', 'ADMIN')",
                SECOND_ADMIN);
        as(bearer(ADMIN, Role.ADMIN), setRole(idOf(ADMIN), "OPERATOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ROLE_CHANGE_REFUSED"))
                .andExpect(jsonPath("$.message").value(UserAdminRules.SELF_DEMOTION));
        assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE email = ?", String.class, ADMIN))
                .isEqualTo("ADMIN");
    }

    @Test
    void theLastAdminCannotBeDemoted() throws Exception {
        // The second admin has just been demoted, but their token (8 hours) still says ADMIN.
        jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES ('Second Admin', ?, 'x', 'OPERATOR')",
                SECOND_ADMIN);
        as(bearer(SECOND_ADMIN, Role.ADMIN), setRole(idOf(ADMIN), "OPERATOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(UserAdminRules.LAST_ADMIN));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anotherAdminCanBeDemotedWhileOneRemains() throws Exception {
        jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES ('Second Admin', ?, 'x', 'ADMIN')",
                SECOND_ADMIN);
        as(bearer(ADMIN, Role.ADMIN), setRole(idOf(SECOND_ADMIN), "MANAGER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void badRequestsAreClear() throws Exception {
        String admin = bearer(ADMIN, Role.ADMIN);
        as(admin, setRole(idOf("tech@corrosionwatch.app"), "AUDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("role must be one of TECHNICIAN, OPERATOR, MANAGER, ADMIN."));
        as(admin, patch("/api/v1/admin/users/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        as(admin, setRole(999_999, "MANAGER")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        as(admin, post("/api/v1/admin/users/999999/unlock")).andExpect(status().isNotFound());
    }
}

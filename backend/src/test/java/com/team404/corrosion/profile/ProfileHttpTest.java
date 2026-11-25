package com.team404.corrosion.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.audit.AuditChainWorker;
import com.team404.corrosion.audit.AuditService;
import com.team404.corrosion.auth.Role;
import com.team404.corrosion.auth.TokenService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Equipment profiles (FR8) end to end on MySQL with the production settings, against the demo profiles from V3/V8:
 * any signed-in user reads, only admins edit, bad values get a 400 that names the problem, and a successful edit
 * writes PROFILE_UPDATED with the old and new values, which the audit worker then chains.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.seed=false",
        "ecp.alerts.scheduler.enabled=false",
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class ProfileHttpTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TokenService tokens;

    @Autowired
    AuditChainWorker worker;

    @Autowired
    AuditService auditService;

    private String bearer(Role role) {
        return "Bearer " + tokens.issue(role.name().toLowerCase() + "@corrosionwatch.app", role.name(), role).value();
    }

    /** Puts profile 2 back to its V3/V8 values and empties the audit tables, so tests do not depend on order. */
    @BeforeEach
    void reset() {
        jdbc.update("UPDATE equipment_profile SET name = 'Telecom cabinet', rh_warn_pct = 60, rh_critical_pct = 75, "
                + "dew_margin_warn_c = 3, dew_margin_critical_c = 1, temp_min_c = 5, temp_max_c = 40, "
                + "surface_offset_c = 1.5 WHERE id = 2");
        jdbc.execute("TRUNCATE TABLE audit_log");
        jdbc.execute("TRUNCATE TABLE audit_outbox");
        jdbc.update("UPDATE audit_chain_head SET last_id = 0, last_hash = ? WHERE id = 1", "0".repeat(64));
    }

    private static Map<String, Object> body() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "Telecom cabinet");
        b.put("rhWarningPct", 62);
        b.put("rhCriticalPct", 78);
        b.put("marginWarningC", 3.5);
        b.put("marginCriticalC", 1.2);
        b.put("tempMinC", 5);
        b.put("tempMaxC", 40);
        b.put("surfaceOffsetC", 2);
        return b;
    }

    private ResultActions putProfile(long id, Role role, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/profiles/" + id).header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void everySignedInRoleCanListTheProfiles() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(get("/api/v1/profiles").header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(4)))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].name").value("IT equipment (ASHRAE)"))
                    .andExpect(jsonPath("$[0].rhWarningPct").value(60.0))
                    .andExpect(jsonPath("$[0].rhCriticalPct").value(70.0))
                    .andExpect(jsonPath("$[0].marginWarningC").value(3.0))
                    .andExpect(jsonPath("$[0].marginCriticalC").value(1.0))
                    .andExpect(jsonPath("$[0].tempMinC").value(18.0))
                    .andExpect(jsonPath("$[0].tempMaxC").value(27.0))
                    .andExpect(jsonPath("$[0].surfaceOffsetC").value(1.5))
                    .andExpect(jsonPath("$[3].tempMinC").value(-20.0));
        }
        mvc.perform(get("/api/v1/profiles")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminUpdatesAProfileAndTheChangeIsAudited() throws Exception {
        putProfile(2, Role.ADMIN, body())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.rhWarningPct").value(62.0))
                .andExpect(jsonPath("$.marginCriticalC").value(1.2))
                .andExpect(jsonPath("$.surfaceOffsetC").value(2.0));

        Map<String, Object> row = jdbc.queryForMap("SELECT rh_warn_pct, dew_margin_critical_c, surface_offset_c "
                + "FROM equipment_profile WHERE id = 2");
        assertThat(row.get("rh_warn_pct").toString()).isEqualTo("62.00");
        assertThat(row.get("dew_margin_critical_c").toString()).isEqualTo("1.20");

        Map<String, Object> event = jdbc.queryForMap("SELECT actor, action, entity_type, entity_id, "
                + "CAST(details AS CHAR) AS details FROM audit_outbox");
        assertThat(event.get("actor")).isEqualTo("admin@corrosionwatch.app");
        assertThat(event.get("action")).isEqualTo("PROFILE_UPDATED");
        assertThat(event.get("entity_type")).isEqualTo("equipment_profile");
        assertThat(event.get("entity_id")).isEqualTo("2");
        JsonNode details = json.readTree((String) event.get("details"));
        assertThat(details.get("old").get("rhWarningPct").decimalValue()).isEqualByComparingTo("60");
        assertThat(details.get("new").get("rhWarningPct").decimalValue()).isEqualByComparingTo("62");
        assertThat(details.get("old").get("surfaceOffsetC").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(details.get("new").get("surfaceOffsetC").decimalValue()).isEqualByComparingTo("2");

        // the audit service chains it like any other event
        assertThat(worker.drainAll()).isEqualTo(1);
        assertThat(auditService.verify("admin@corrosionwatch.app").valid()).isTrue();
    }

    @Test
    void savingTheSameValuesWritesNoEvent() throws Exception {
        Map<String, Object> same = body();
        same.put("rhWarningPct", 60);
        same.put("rhCriticalPct", 75);
        same.put("marginWarningC", 3);
        same.put("marginCriticalC", 1);
        same.put("surfaceOffsetC", 1.5);
        putProfile(2, Role.ADMIN, same).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox", Long.class)).isZero();
    }

    @Test
    void onlyAdminsMayUpdateAndRefusalsAreRecorded() throws Exception {
        for (Role role : List.of(Role.TECHNICIAN, Role.OPERATOR, Role.MANAGER)) {
            putProfile(2, role, body())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Administrator access required."));
        }
        assertThat(jdbc.queryForObject("SELECT rh_warn_pct FROM equipment_profile WHERE id = 2", String.class))
                .isEqualTo("60.00");
        assertThat(jdbc.queryForList("SELECT action FROM audit_outbox", String.class))
                .containsExactly("ACCESS_DENIED", "ACCESS_DENIED", "ACCESS_DENIED");
        mvc.perform(put("/api/v1/profiles/2").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body()))).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidValuesGetAClear400AndChangeNothing() throws Exception {
        Map<String, Object> margins = body();
        margins.put("marginWarningC", 1);
        margins.put("marginCriticalC", 1);
        putProfile(2, Role.ADMIN, margins)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PROFILE"))
                .andExpect(jsonPath("$.message").value("Warning margin must be greater than the critical margin."));

        Map<String, Object> rh = body();
        rh.put("rhCriticalPct", 101);
        rh.put("surfaceOffsetC", -1);
        putProfile(2, Role.ADMIN, rh)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.problems", hasSize(2)))
                .andExpect(jsonPath("$.message", containsString("Critical humidity must be at most 100 %.")))
                .andExpect(jsonPath("$.message", containsString("Surface offset must be 0 °C or more.")));

        Map<String, Object> missing = body();
        missing.remove("marginCriticalC");
        putProfile(2, Role.ADMIN, missing)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("marginCriticalC is required."));

        assertThat(jdbc.queryForObject("SELECT rh_warn_pct FROM equipment_profile WHERE id = 2", String.class))
                .isEqualTo("60.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox", Long.class)).isZero();
    }

    @Test
    void unknownProfileIs404AndATakenNameIs409() throws Exception {
        putProfile(999, Role.ADMIN, body()).andExpect(status().isNotFound());
        Map<String, Object> taken = body();
        taken.put("name", "Outdoor enclosure");
        putProfile(2, Role.ADMIN, taken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NAME_TAKEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox", Long.class)).isZero();
    }
}

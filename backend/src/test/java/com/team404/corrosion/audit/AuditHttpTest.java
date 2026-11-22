package com.team404.corrosion.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.auth.Role;
import com.team404.corrosion.auth.TokenService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The audit endpoints over HTTP with the real security filter chain, on MySQL with the production settings:
 * paging and hashes on the list, who may verify (MANAGER and ADMIN), checkpoints admin-only with refusals recorded as
 * ACCESS_DENIED, and a tampered chain reported broken. Tokens come from the real TokenService, one per role.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.seed=false",
        "ecp.alerts.scheduler.enabled=false",
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class AuditHttpTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TokenService tokens;

    @Autowired
    AuditChainWorker worker;

    private String bearer(Role role) {
        String email = role.name().toLowerCase() + "@corrosionwatch.app";
        return "Bearer " + tokens.issue(email, role.name(), role).value();
    }

    @BeforeEach
    void emptyAudit() {
        jdbc.execute("TRUNCATE TABLE audit_log");
        jdbc.execute("TRUNCATE TABLE audit_outbox");
        jdbc.execute("TRUNCATE TABLE audit_checkpoint");
        jdbc.execute("TRUNCATE TABLE audit_verification");
        jdbc.update("UPDATE audit_chain_head SET last_id = 0, last_hash = ? WHERE id = 1", HashChain.GENESIS_HASH);
    }

    @Test
    void listIsOnePageWithTotalAndHashes() throws Exception {
        chain(5);

        mvc.perform(get("/api/v1/audit?page=0&size=2").header("Authorization", bearer(Role.MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(5))
                .andExpect(jsonPath("$.items[0].hash").value(hashOf(5)))
                .andExpect(jsonPath("$.items[0].prevHash").value(hashOf(4)))
                .andExpect(jsonPath("$.items[1].hash").value(hashOf(4)));

        mvc.perform(get("/api/v1/audit?page=2&size=2").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].prevHash").value(HashChain.GENESIS_HASH));
    }

    @Test
    void pageSizeIsCappedAt200AndDefaultsTo50() throws Exception {
        chain(1);
        mvc.perform(get("/api/v1/audit?size=500").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mvc.perform(get("/api/v1/audit").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void managersAndAdminsMayVerify() throws Exception {
        chain(3);
        mvc.perform(post("/api/v1/audit/verify").header("Authorization", bearer(Role.MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.checked").value(3));
        mvc.perform(post("/api/v1/audit/verify").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
        assertThat(actions()).containsExactly("AUDIT_VERIFIED", "AUDIT_VERIFIED");
    }

    @Test
    void techniciansOperatorsAndAnonymousMayNotVerify() throws Exception {
        mvc.perform(post("/api/v1/audit/verify").header("Authorization", bearer(Role.TECHNICIAN)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/audit/verify").header("Authorization", bearer(Role.OPERATOR)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/audit/verify")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_verification", Long.class)).isZero();
    }

    @Test
    void checkpointsAreAdminOnlyAndRefusalsAreRecorded() throws Exception {
        chain(2);
        mvc.perform(post("/api/v1/audit/checkpoints").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].lastId").value(2))
                .andExpect(jsonPath("$[0].lastHash").value(hashOf(2)));
        mvc.perform(get("/api/v1/audit/checkpoints").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(get("/api/v1/audit/checkpoints").header("Authorization", bearer(Role.MANAGER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Administrator access required."));
        mvc.perform(post("/api/v1/audit/checkpoints").header("Authorization", bearer(Role.MANAGER)))
                .andExpect(status().isForbidden());

        List<String> denied = jdbc.queryForList("SELECT CONCAT(actor, ' ', CAST(details AS CHAR)) FROM audit_outbox "
                + "WHERE action = 'ACCESS_DENIED' ORDER BY id", String.class);
        assertThat(denied).hasSize(2);
        assertThat(denied.get(0)).startsWith("manager@corrosionwatch.app").contains("GET /api/v1/audit/checkpoints");
        assertThat(denied.get(1)).contains("POST /api/v1/audit/checkpoints");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_checkpoint", Long.class)).isEqualTo(1L);
    }

    @Test
    void tamperedChainIsReportedBroken() throws Exception {
        chain(3);
        AuditTampering.whileTriggersAreOff(jdbc,
                () -> jdbc.update("UPDATE audit_log SET actor = 'mallory' WHERE id = 2"));

        mvc.perform(post("/api/v1/audit/verify").header("Authorization", bearer(Role.MANAGER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.firstBrokenId").value(2))
                .andExpect(jsonPath("$.checked").value(1));
        assertThat(jdbc.queryForObject("SELECT valid FROM audit_verification", Boolean.class)).isFalse();
    }

    // ---- helpers ----

    private void chain(int n) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        for (int i = 1; i <= n; i++) {
            jdbc.update("INSERT INTO audit_outbox (occurred_at, actor_type, actor, action, entity_type, entity_id, "
                    + "details) VALUES (?, 'USER', ?, 'USER_LOGIN', 'user', ?, '{}')", now, "u" + i, String.valueOf(i));
        }
        assertThat(worker.drainAll()).isEqualTo(n);
    }

    private String hashOf(long id) {
        return jdbc.queryForObject("SELECT entry_hash FROM audit_log WHERE id = ?", String.class, id);
    }

    private List<String> actions() {
        return jdbc.queryForList("SELECT action FROM audit_outbox WHERE chained_at IS NULL ORDER BY id", String.class);
    }
}

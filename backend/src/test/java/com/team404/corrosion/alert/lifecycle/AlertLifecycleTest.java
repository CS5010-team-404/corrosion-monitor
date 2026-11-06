package com.team404.corrosion.alert.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.alert.AlertBroadcaster;
import com.team404.corrosion.alert.AlertEvents;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import com.team404.corrosion.alert.signal.JdbcAlertStore;
import com.team404.corrosion.alert.signal.Signal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The alert endpoints and the escalation job on MySQL with the production settings. */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.seed=false",
        // keep the background jobs out of the way; the tests call the escalation job themselves
        "ecp.alerts.poll-interval=PT1H", "ecp.alerts.escalation-interval=PT1H"})
@AutoConfigureMockMvc
@Import(AlertLifecycleTest.Recording.class)
@Testcontainers
class AlertLifecycleTest {

    static final List<String> PUSHED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Recording {
        @Bean
        @org.springframework.context.annotation.Primary
        AlertBroadcaster recordingBroadcaster() {
            return (topic, payload) -> PUSHED.add(topic + " " + payload);
        }
    }

    private static final String TECH = "tech@test.local";
    private static final String OPERATOR = "operator@test.local";
    private static final String MANAGER = "manager@test.local";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AlertRepository repository;

    @Autowired
    AlertEvents events;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM corrective_action");
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM audit_outbox");
        PUSHED.clear();
        user(TECH, "Tara Tech", "TECHNICIAN");
        user(OPERATOR, "Olive Operator", "OPERATOR");
        user(MANAGER, "Max Manager", "MANAGER");
    }

    @Test
    void acknowledgeThenResolveWalksTheLifecycle() throws Exception {
        long id = alert("CRITICAL", "OPEN", Instant.now());

        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(TECH, "TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedBy").value("Tara Tech"));

        mvc.perform(post("/api/v1/alerts/{id}/resolve", id).with(as(TECH, "TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"Dehumidifier fixed it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value("Tara Tech"));

        mvc.perform(get("/api/v1/alerts/{id}", id).with(as(OPERATOR, "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.status").value("RESOLVED"))
                .andExpect(jsonPath("$.actions", hasSize(1)))
                .andExpect(jsonPath("$.actions[0].actionType").value("RESOLVED"))
                .andExpect(jsonPath("$.actions[0].notes").value("Dehumidifier fixed it"))
                .andExpect(jsonPath("$.actions[0].username").value("Tara Tech"));
    }

    @Test
    void anOpenAlertCanBeResolvedWithoutNotesOrBody() throws Exception {
        long id = alert("WARNING", "OPEN", Instant.now());

        mvc.perform(post("/api/v1/alerts/{id}/resolve", id).with(as(TECH, "TECHNICIAN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM corrective_action", Long.class)).isZero();
    }

    @Test
    void doingSomethingTwiceOrOutOfOrderIsAConflict() throws Exception {
        long id = alert("WARNING", "ACKNOWLEDGED", Instant.now());
        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(TECH, "TECHNICIAN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INVALID_STATE"));

        long resolved = alert("WARNING", "RESOLVED", Instant.now());
        mvc.perform(post("/api/v1/alerts/{id}/resolve", resolved).with(as(TECH, "TECHNICIAN")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/alerts/{id}/actions", resolved).with(as(TECH, "TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\":\"NOTE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void everyUserActionWritesAnAuditEventNamingTheUserAndPushesANotice() throws Exception {
        long id = alert("WARNING", "OPEN", Instant.now());

        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(TECH, "TECHNICIAN"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/alerts/{id}/actions", id).with(as(TECH, "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\":\"INSPECTED\",\"notes\":\"Seal intact\"}"));
        mvc.perform(post("/api/v1/alerts/{id}/resolve", id).with(as(TECH, "TECHNICIAN")));

        assertThat(jdbc.queryForList("SELECT action FROM audit_outbox ORDER BY id", String.class))
                .containsExactly("ALERT_ACKNOWLEDGED", "CORRECTIVE_ACTION_LOGGED", "ALERT_RESOLVED");
        assertThat(jdbc.queryForList("SELECT DISTINCT actor FROM audit_outbox", String.class)).containsExactly(TECH);
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_type FROM audit_outbox", String.class)).containsExactly("USER");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(details, '$.notes')) FROM audit_outbox "
                + "WHERE action = 'CORRECTIVE_ACTION_LOGGED'", String.class)).isEqualTo("Seal intact");
        assertThat(PUSHED).hasSize(3);
    }

    @Test
    void aRefusedRequestLeavesNoAuditEventAndNoNotice() throws Exception {
        long id = alert("WARNING", "RESOLVED", Instant.now());

        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(TECH, "TECHNICIAN"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/alerts/{id}/resolve", id).with(as(TECH, "TECHNICIAN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notes\":\"late\"}")).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM corrective_action", Long.class)).isZero();
        assertThat(PUSHED).isEmpty();
    }

    @Test
    void anUnknownAlertIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/alerts/999999").with(as(OPERATOR, "OPERATOR"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/alerts/999999/acknowledge").with(as(TECH, "TECHNICIAN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("ALERT_NOT_FOUND"));
    }

    @Test
    void correctiveActionsKeepTypeAndNotes() throws Exception {
        long id = alert("WARNING", "OPEN", Instant.now());

        mvc.perform(post("/api/v1/alerts/{id}/actions", id).with(as(TECH, "TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"DEHUMIDIFIER_ON\",\"notes\":\"Set to 45%\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionType").value("DEHUMIDIFIER_ON"))
                .andExpect(jsonPath("$.notes").value("Set to 45%"))
                .andExpect(jsonPath("$.username").value("Tara Tech"));

        mvc.perform(post("/api/v1/alerts/{id}/actions", id).with(as(TECH, "TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTechniciansAndAdminsMayAct() throws Exception {
        long id = alert("WARNING", "OPEN", Instant.now());

        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(OPERATOR, "OPERATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(MANAGER, "MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/alerts").with(as(MANAGER, "MANAGER"))).andExpect(status().isOk());
    }

    @Test
    void listFiltersByStatusAndRejectsAnUnknownOne() throws Exception {
        alert("WARNING", "OPEN", Instant.now());
        alert("WARNING", "ACKNOWLEDGED", Instant.now());
        alert("WARNING", "RESOLVED", Instant.now());

        mvc.perform(get("/api/v1/alerts").with(as(OPERATOR, "OPERATOR"))).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/v1/alerts?status=ACTIVE").with(as(OPERATOR, "OPERATOR"))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/alerts?status=OPEN").with(as(OPERATOR, "OPERATOR"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/alerts?status=ALL&limit=2").with(as(OPERATOR, "OPERATOR"))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/alerts?status=CLOSED").with(as(OPERATOR, "OPERATOR"))).andExpect(status().isBadRequest());
    }

    @Test
    void escalatesAnUnacknowledgedCriticalAlertOnceAfterFiveMinutes() {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");
        long stale = alert("CRITICAL", "OPEN", now.minus(Duration.ofMinutes(5)));
        long fresh = alert("CRITICAL", "OPEN", now.minus(Duration.ofMinutes(4)));
        long acknowledged = alert("CRITICAL", "ACKNOWLEDGED", now.minus(Duration.ofMinutes(30)));
        long warning = alert("WARNING", "OPEN", now.minus(Duration.ofMinutes(30)));
        var sent = new ArrayList<Long>();
        var managers = new ArrayList<List<String>>();
        var job = new EscalationJob(repository, (a, emails) -> {
            sent.add(a.id());
            managers.add(emails);
        }, events, tx, fixed(now), Duration.ofMinutes(5));

        assertThat(job.escalateDue()).isEqualTo(1);
        assertThat(job.escalateDue()).isZero();

        assertThat(sent).containsExactly(stale);
        assertThat(managers.get(0)).containsExactly(MANAGER);
        assertThat(level(stale)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_outbox", String.class)).containsExactly("ALERT_ESCALATED");
        assertThat(PUSHED).hasSize(1);
        assertThat(level(fresh)).isZero();
        assertThat(level(acknowledged)).isZero();
        assertThat(level(warning)).isZero();

        // The fresh alert crosses the line a minute later.
        var later = new EscalationJob(repository, (a, emails) -> sent.add(a.id()), events, tx, fixed(now.plusSeconds(60)),
                Duration.ofMinutes(5));
        assertThat(later.escalateDue()).isEqualTo(1);
        assertThat(sent).containsExactly(stale, fresh);
    }

    @Test
    void aWarningThatTurnsCriticalGetsTheFullFiveMinutesFromThen() {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");
        long id = alert("WARNING", "OPEN", now.minus(Duration.ofMinutes(30)));
        new JdbcAlertStore(jdbc).refresh(id, critical(), now.minus(Duration.ofMinutes(2)));
        var sent = new ArrayList<Long>();

        assertThat(new EscalationJob(repository, (a, e) -> sent.add(a.id()), events, tx, fixed(now),
                Duration.ofMinutes(5)).escalateDue()).isZero();
        assertThat(new EscalationJob(repository, (a, e) -> sent.add(a.id()), events, tx,
                fixed(now.plus(Duration.ofMinutes(3))), Duration.ofMinutes(5)).escalateDue()).isEqualTo(1);
        assertThat(sent).containsExactly(id);
    }

    @Test
    void aRepeatedCriticalSignalDoesNotRestartTheEscalationClock() {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");
        long id = alert("WARNING", "OPEN", now.minus(Duration.ofMinutes(30)));
        var store = new JdbcAlertStore(jdbc);
        store.refresh(id, critical(), now.minus(Duration.ofMinutes(6)));
        store.refresh(id, critical(), now.minus(Duration.ofMinutes(1)));

        assertThat(new EscalationJob(repository, (a, e) -> { }, events, tx, fixed(now), Duration.ofMinutes(5))
                .escalateDue()).isEqualTo(1);
    }

    private static Signal critical() {
        return new Signal(1, "ANALYTICS", "CONDENSATION_RISK", "CRITICAL", Signal.RAISED, null, null, null, "k",
                "Test alert", "Margin is low", Instant.now());
    }

    @Test
    void anAlertAcknowledgedBeforeTheDeadlineIsNotEscalated() throws Exception {
        Instant now = Instant.now();
        long id = alert("CRITICAL", "OPEN", now.minus(Duration.ofMinutes(4)));
        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", id).with(as(TECH, "TECHNICIAN"))).andExpect(status().isOk());
        var sent = new ArrayList<Long>();
        var job = new EscalationJob(repository, (a, e) -> sent.add(a.id()), events, tx, fixed(now.plus(Duration.ofMinutes(10))),
                Duration.ofMinutes(5));

        assertThat(job.escalateDue()).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void theAlertViewNamesTheEquipmentAndLocation() {
        long equipmentId = jdbc.queryForObject("SELECT MIN(id) FROM equipment", Long.class);
        long id = alert("WARNING", "OPEN", Instant.now());
        jdbc.update("UPDATE alert SET equipment_id = ?, location_id = (SELECT location_id FROM equipment WHERE id = ?) "
                + "WHERE id = ?", equipmentId, equipmentId, id);

        AlertView view = repository.find(id).orElseThrow();

        assertThat(view.equipmentName()).isEqualTo(
                jdbc.queryForObject("SELECT name FROM equipment WHERE id = ?", String.class, equipmentId));
        assertThat(view.locationName()).isNotNull();
        assertThat(view.title()).isEqualTo("Test alert");
    }

    private long level(long id) {
        return jdbc.queryForObject("SELECT escalation_level FROM alert WHERE id = ?", Long.class, id);
    }

    private long alert(String severity, String status, Instant openedAt) {
        jdbc.update("""
                INSERT INTO alert (type, severity, title, message, status, opened_at, source)
                VALUES ('CONDENSATION_RISK', ?, 'Test alert', 'Margin is low', ?, ?, 'ANALYTICS')
                """, severity, status, Timestamp.from(openedAt));
        return jdbc.queryForObject("SELECT MAX(id) FROM alert", Long.class);
    }

    private void user(String email, String name, String role) {
        jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES (?, ?, 'x', ?)", name, email, role);
    }

    private static RequestPostProcessor as(String email, String role) {
        return jwt().jwt(j -> j.subject(email)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static Clock fixed(Instant now) {
        return new Clock() {
            @Override public Instant instant() { return now; }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
    }
}

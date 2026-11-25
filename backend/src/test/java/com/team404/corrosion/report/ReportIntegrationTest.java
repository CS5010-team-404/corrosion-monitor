package com.team404.corrosion.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.alert.lifecycle.EscalationJob;
import com.team404.corrosion.alert.lifecycle.ManagerNotifier;
import com.team404.corrosion.alert.signal.SignalConsumer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR7 end to end on MySQL with the production settings: analytics signals open alerts, a technician acknowledges,
 * logs an action and resolves, the escalation job runs, and the report shows exactly those numbers. Also who may
 * read reports.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.seed=false",
        "ecp.alerts.scheduler.enabled=false",
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(ReportIntegrationTest.TestBeans.class)
@Testcontainers
class ReportIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");
    private static final String TECH = "tech@test.local";
    private static final String MANAGER = "manager@test.local";
    private static final List<String> PATHS =
            List.of("/api/v1/reports/summary", "/api/v1/reports/summary.csv", "/api/v1/reports/summary.pdf");

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    static final TestClock CLOCK = new TestClock();

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        ManagerNotifier quietNotifier() {
            return (alert, emails) -> { };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SignalConsumer signals;

    @Autowired
    EscalationJob escalation;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM corrective_action");
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM alert_signals");
        jdbc.update("DELETE FROM audit_outbox");
        jdbc.update("DELETE FROM app_user");
        user(TECH, "Tara Tech", "TECHNICIAN");
        user(MANAGER, "Max Manager", "MANAGER");
        CLOCK.now = T0;
    }

    @Test
    void reportNumbersMatchWhatHappenedToTheAlerts() throws Exception {
        // two equipment items in different locations
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT MIN(e.id) AS id, l.name AS location FROM equipment e JOIN location l ON l.id = e.location_id
                GROUP BY l.name ORDER BY MIN(e.id)
                """);
        assertThat(items).hasSizeGreaterThanOrEqualTo(2);
        long rack = ((Number) items.get(0).get("id")).longValue();
        String rackLocation = (String) items.get(0).get("location");
        long other = ((Number) items.get(1).get("id")).longValue();

        // 08:00 analytics raises a CRITICAL risk on one item and a WARNING forecast on the other
        signal("CONDENSATION_RISK", "CRITICAL", rack);
        signal("CONDENSATION_FORECAST", "WARNING", other);
        signals.poll();
        long critical = alertId("ANALYTICS:CONDENSATION_RISK:" + rack);
        long warning = alertId("ANALYTICS:CONDENSATION_FORECAST:" + other);

        // 08:05 nobody has acknowledged the critical one: it escalates
        at(5);
        escalation.poll();
        // 08:06 acknowledged, 08:08 an action, 08:20 resolved with a note (a second action)
        at(6);
        mvc.perform(post("/api/v1/alerts/{id}/acknowledge", critical).with(as(TECH, "TECHNICIAN"))).andExpect(status().isOk());
        at(8);
        mvc.perform(post("/api/v1/alerts/{id}/actions", critical).with(as(TECH, "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"actionType\":\"DEHUMIDIFIER_ON\",\"notes\":\"45%\"}"))
                .andExpect(status().isCreated());
        at(20);
        mvc.perform(post("/api/v1/alerts/{id}/resolve", critical).with(as(TECH, "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"Dry again\"}")).andExpect(status().isOk());
        // 08:30 the warning is resolved straight from OPEN
        at(30);
        mvc.perform(post("/api/v1/alerts/{id}/resolve", warning).with(as(TECH, "TECHNICIAN"))).andExpect(status().isOk());

        String day = "?from=2026-11-10T00:00:00Z&to=2026-11-11T00:00:00Z";
        mvc.perform(get("/api/v1/reports/summary" + day).with(as(MANAGER, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-10T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-11-11T00:00:00Z"))
                .andExpect(jsonPath("$.alertsOpened").value(2))
                .andExpect(jsonPath("$.alertsBySeverity.CRITICAL").value(1))
                .andExpect(jsonPath("$.alertsBySeverity.WARNING").value(1))
                .andExpect(jsonPath("$.meanMinutesToAcknowledge").value(6.0))
                .andExpect(jsonPath("$.meanMinutesToResolve").value(25.0))   // (20 + 30) / 2
                .andExpect(jsonPath("$.escalations").value(1))
                .andExpect(jsonPath("$.correctiveActions").value(2))
                .andExpect(jsonPath("$.correctiveActionsByType.DEHUMIDIFIER_ON").value(1))
                .andExpect(jsonPath("$.correctiveActionsByType.RESOLVED").value(1))
                .andExpect(jsonPath("$.equipment").isArray());

        // one location: only the critical alert and its actions
        mvc.perform(get("/api/v1/reports/summary" + day).param("location", rackLocation).with(as(MANAGER, "MANAGER")))
                .andExpect(jsonPath("$.location").value(rackLocation))
                .andExpect(jsonPath("$.alertsOpened").value(1))
                .andExpect(jsonPath("$.meanMinutesToResolve").value(20.0))
                .andExpect(jsonPath("$.correctiveActions").value(2));

        // a period after everything happened is empty
        mvc.perform(get("/api/v1/reports/summary?from=2026-11-10T09:00:00Z").with(as(MANAGER, "MANAGER")))
                .andExpect(jsonPath("$.alertsOpened").value(0))
                .andExpect(jsonPath("$.meanMinutesToAcknowledge").value(nullValue()))
                .andExpect(jsonPath("$.escalations").value(0))
                .andExpect(jsonPath("$.correctiveActions").value(0));

        // the CSV and the PDF carry the same numbers
        mvc.perform(get("/api/v1/reports/summary.csv" + day).with(as(MANAGER, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("corrosion-watch-report.csv")))
                .andExpect(content().string(containsString("Alerts,Alerts opened,2\r\n")))
                .andExpect(content().string(containsString("Alerts,Mean minutes to acknowledge,6.0\r\n")))
                .andExpect(content().string(containsString("Alerts,Mean minutes to resolve,25.0\r\n")))
                .andExpect(content().string(containsString("Alerts,Escalations,1\r\n")))
                .andExpect(content().string(containsString("Corrective actions,DEHUMIDIFIER_ON,1\r\n")));
        mvc.perform(get("/api/v1/reports/summary.pdf" + day).with(as(MANAGER, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("corrosion-watch-report.pdf")))
                .andExpect(content().string(startsWith("%PDF-")));
    }

    @Test
    void managersAndAdminsMayReadReports() throws Exception {
        for (String path : PATHS) {
            mvc.perform(get(path).with(as(MANAGER, "MANAGER"))).andExpect(status().isOk());
            mvc.perform(get(path).with(as("admin@test.local", "ADMIN"))).andExpect(status().isOk());
        }
    }

    @Test
    void techniciansAndOperatorsMayNot() throws Exception {
        for (String path : PATHS) {
            mvc.perform(get(path).with(as(TECH, "TECHNICIAN"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(as("operator@test.local", "OPERATOR"))).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aBadPeriodIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/reports/summary?from=yesterday").with(as(MANAGER, "MANAGER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("'from'")));
        mvc.perform(get("/api/v1/reports/summary.csv?from=2026-11-11T00:00:00Z&to=2026-11-10T00:00:00Z")
                        .with(as(MANAGER, "MANAGER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'from' must be before 'to'."));
    }

    private void at(long minutesAfterT0) {
        CLOCK.now = T0.plus(Duration.ofMinutes(minutesAfterT0));
    }

    private void signal(String type, String severity, long equipmentId) {
        jdbc.update("""
                INSERT INTO alert_signals (source, signal_type, severity, state, equipment_id, dedup_key, title, message,
                                           payload, created_at)
                VALUES ('ANALYTICS', ?, ?, 'RAISED', ?, ?, NULL, 'Condensation margin is low', '{}', ?)
                """, type, severity, equipmentId, "ANALYTICS:" + type + ":" + equipmentId, Timestamp.from(CLOCK.now));
    }

    private long alertId(String dedupKey) {
        return jdbc.queryForObject("SELECT id FROM alert WHERE dedup_key = ?", Long.class, dedupKey);
    }

    private void user(String email, String name, String role) {
        jdbc.update("INSERT INTO app_user (name, email, password_hash, role) VALUES (?, ?, 'x', ?)", name, email, role);
    }

    private static RequestPostProcessor as(String email, String role) {
        return jwt().jwt(j -> j.subject(email)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static final class TestClock extends Clock {
        volatile Instant now = T0;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

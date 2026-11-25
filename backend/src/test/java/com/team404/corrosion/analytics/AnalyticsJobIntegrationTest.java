package com.team404.corrosion.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import com.team404.corrosion.alert.signal.SignalConsumer;
import com.team404.corrosion.analytics.signal.AnalyticsJob;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The analytics job end to end on MySQL with the production settings (ANSI sql_mode, required primary keys): humid
 * readings raise a condensation signal once, dry readings clear it, the alert service turns the rows into an alert,
 * the evaluations are stored and served by GET /api/v1/equipment/{id}/assessments, old ones are pruned, the
 * platform's forecast fills the status and raises its own signal, and the stored assessments feed the report figures.
 *
 * <p>The job's timer is off for every test (src/test/resources/config/application.properties); the tests call
 * {@link AnalyticsJob#runOnce()} themselves. The platform's tables (sensors, sensor_health, readings,
 * sensor_forecasts) come from the platform's own migration files, as in EquipmentStatusIntegrationTest. Every test
 * makes its own equipment, and looks only at the rows of its own dedup keys, because the job evaluates every item.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.password=" + AnalyticsJobIntegrationTest.PASSWORD,
        "ecp.analytics.signals.enabled=false",
        "ecp.alerts.scheduler.enabled=false",
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class AnalyticsJobIntegrationTest {

    static final String PASSWORD = "demo-password-for-tests";

    private static final Path PLATFORM_DB = Path.of("../platform/ecp-platform-app/src/main/resources/db/platform")
            .toAbsolutePath().normalize();

    /** The fields of AssessmentPoint in frontend/src/api/types.ts. */
    private static final Set<String> POINT_FIELDS = new TreeSet<>(List.of(
            "measuredAt", "temperatureC", "humidityPct", "surfaceTempC", "dewPointC", "marginC"));

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    AnalyticsJob job;

    @Autowired
    AnalyticsService analytics;

    @Autowired
    AssessmentRepo assessmentRepo;

    @Autowired
    SignalConsumer alertSignals;

    private long uniqueCounter;
    private int seq;

    @BeforeEach
    void createPlatformTables() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource(PLATFORM_DB.resolve("V1__platform_tables.sql")));
            ScriptUtils.executeSqlScript(c, new FileSystemResource(PLATFORM_DB.resolve("V2__sensor_forecasts.sql")));
        }
    }

    // ----------------------------------------------------------------------------------------------- the workflow

    @Test
    void humidReadingsRaiseOnceAndDryReadingsClear() {
        Item item = trustedItem("Workflow");
        reading(item.sensorId, 3, 25.0, 90.0, 20.0);   // the surface is below the 23.2 C dew point: CRITICAL
        reading(item.sensorId, 2, 25.0, 90.0, 20.0);

        job.runOnce();

        List<Map<String, Object>> rows = signalRows(riskKey(item));
        assertThat(rows).hasSize(1);
        Map<String, Object> raised = rows.get(0);
        assertThat(raised.get("state")).isEqualTo("RAISED");
        assertThat(raised.get("severity")).isEqualTo("CRITICAL");
        assertThat(raised.get("source")).isEqualTo("ANALYTICS");
        assertThat(raised.get("signal_type")).isEqualTo("CONDENSATION_RISK");
        assertThat(raised.get("equipment_id")).isEqualTo(item.equipmentId);
        assertThat(raised.get("sensor_id")).isEqualTo(item.sensorId);
        assertThat((String) raised.get("message")).contains("Dew-point margin is -");
        assertThat(payload(raised).get("deviceId").asText()).isEqualTo(item.deviceId);

        job.runOnce();
        job.runOnce();
        assertThat(signalRows(riskKey(item))).as("no repeat while it stays").hasSize(1);

        reading(item.sensorId, 0, 25.0, 50.0, 20.0);   // dry again: margin about 6 C
        job.runOnce();

        rows = signalRows(riskKey(item));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("state")).isEqualTo("CLEARED");
        assertThat(rows.get(1).get("severity")).isEqualTo("CRITICAL");

        job.runOnce();
        assertThat(signalRows(riskKey(item))).as("no repeat after the clear").hasSize(2);
    }

    @Test
    void anEpisodeThatStartedBeforeARestartIsNotRaisedAgain() {
        // the state is read from alert_signals itself, so a new job run (as after a restart) sees the open episode
        Item item = trustedItem("Restart");
        reading(item.sensorId, 1, 25.0, 90.0, 20.0);
        job.runOnce();
        job.runOnce();
        assertThat(signalRows(riskKey(item))).hasSize(1);
    }

    @Test
    void severityMovesWithTheLevel() {
        Item item = trustedItem("Severity");
        reading(item.sensorId, 3, 25.0, 55.0, 17.5);   // margin about 1.8 C: WARNING
        job.runOnce();
        reading(item.sensorId, 2, 25.0, 90.0, 20.0);   // CRITICAL
        job.runOnce();

        List<Map<String, Object>> rows = signalRows(riskKey(item));
        assertThat(rows).extracting(r -> r.get("state") + " " + r.get("severity"))
                .containsExactly("RAISED WARNING", "RAISED CRITICAL");
    }

    @Test
    void theAlertServiceTurnsTheSignalsIntoOneAlertAndRecordsTheClear() {
        Item item = trustedItem("AlertFlow");
        reading(item.sensorId, 3, 25.0, 90.0, 20.0);
        job.runOnce();
        job.runOnce();
        alertSignals.poll();

        List<Map<String, Object>> alerts = jdbc.queryForList(
                "SELECT severity, title, status, device_id, source, type, condition_cleared_at FROM alert WHERE dedup_key = ?",
                riskKey(item));
        assertThat(alerts).hasSize(1);
        Map<String, Object> alert = alerts.get(0);
        assertThat(alert.get("severity")).isEqualTo("CRITICAL");
        assertThat(alert.get("status")).isEqualTo("OPEN");
        assertThat(alert.get("source")).isEqualTo("ANALYTICS");
        assertThat(alert.get("type")).isEqualTo("CONDENSATION_RISK");
        assertThat(alert.get("device_id")).isEqualTo(item.deviceId);
        assertThat((String) alert.get("title")).startsWith("Condensation risk: Rack AlertFlow-");
        assertThat(alert.get("condition_cleared_at")).isNull();

        reading(item.sensorId, 0, 25.0, 50.0, 20.0);
        job.runOnce();
        alertSignals.poll();

        assertThat(jdbc.queryForObject("SELECT condition_cleared_at FROM alert WHERE dedup_key = ?",
                java.sql.Timestamp.class, riskKey(item))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert WHERE dedup_key = ?", Integer.class, riskKey(item)))
                .isEqualTo(1);
    }

    @Test
    void anUntrustedSensorWritesNoSignalAndNoAssessment() {
        Item item = untrustedItem("Untrusted");
        reading(item.sensorId, 1, 25.0, 90.0, 20.0);   // would be CRITICAL if it were trusted

        job.runOnce();

        assertThat(signalRows(riskKey(item))).isEmpty();
        assertThat(countAssessments(item.equipmentId)).isZero();
    }

    @Test
    void aSensorThatTurnsUntrustedLeavesTheEpisodeOpen() {
        Item item = trustedItem("GoesUntrusted");
        reading(item.sensorId, 2, 25.0, 90.0, 20.0);
        job.runOnce();

        jdbc.update("UPDATE sensor_health SET trusted = FALSE, status = 'STUCK' WHERE sensor_id = ?", item.sensorId);
        job.runOnce();
        job.runOnce();

        assertThat(signalRows(riskKey(item))).as("neither cleared nor raised again").hasSize(1);
    }

    // ----------------------------------------------------------------------------------------------- assessments

    @Test
    void assessmentsAreStoredOncePerReadingAndServedAsAssessmentPoints() throws Exception {
        Item item = trustedItem("Points");
        reading(item.sensorId, 2, 25.0, 50.0, 20.0);

        job.runOnce();
        job.runOnce();   // the same reading again: nothing new to store
        assertThat(countAssessments(item.equipmentId)).isEqualTo(1);

        reading(item.sensorId, 1, 26.0, 52.0, 21.0);
        job.runOnce();
        assertThat(countAssessments(item.equipmentId)).isEqualTo(2);

        JsonNode points = getJson("/api/v1/equipment/" + item.equipmentId + "/assessments?minutes=60");

        assertThat(points.isArray()).isTrue();
        assertThat(points).hasSize(2);
        for (JsonNode point : points) {
            assertThat(fieldNames(point)).isEqualTo(POINT_FIELDS);
        }
        JsonNode first = points.get(0);
        assertThat(first.get("temperatureC").asDouble()).isEqualTo(25.0);
        assertThat(first.get("humidityPct").asDouble()).isEqualTo(50.0);
        assertThat(first.get("surfaceTempC").asDouble()).isEqualTo(20.0);
        assertThat(first.get("dewPointC").asDouble()).isCloseTo(13.85, org.assertj.core.data.Offset.offset(0.02));
        assertThat(first.get("marginC").asDouble()).isCloseTo(6.15, org.assertj.core.data.Offset.offset(0.02));
        assertThat(Instant.parse(first.get("measuredAt").asText()))
                .isBefore(Instant.parse(points.get(1).get("measuredAt").asText()));   // oldest first
        assertThat(points.get(1).get("temperatureC").asDouble()).isEqualTo(26.0);
    }

    @Test
    void theAssessmentsEndpointHonoursMinutes() throws Exception {
        Item item = trustedItem("Minutes");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assessmentRepo.insert(List.of(
                row(item, now.minusSeconds(40 * 60), "NORMAL"),
                row(item, now.minusSeconds(5 * 60), "NORMAL")));

        assertThat(getJson("/api/v1/equipment/" + item.equipmentId + "/assessments?minutes=10")).hasSize(1);
        assertThat(getJson("/api/v1/equipment/" + item.equipmentId + "/assessments?minutes=60")).hasSize(2);
        assertThat(getJson("/api/v1/equipment/" + item.equipmentId + "/assessments")).as("default is 60 minutes")
                .hasSize(2);
        assertThat(getJson("/api/v1/equipment/" + item.equipmentId + "/assessments?minutes=1000000"))
                .as("cut to the seven days that are kept").hasSize(2);
    }

    @Test
    void theAssessmentsOfAnUnknownEquipmentItemAreNotFound() throws Exception {
        mvc.perform(get("/api/v1/equipment/987654/assessments").header("Authorization", "Bearer " + signIn()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAssessmentsEndpointNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/v1/equipment/1/assessments")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlySevenDaysOfAssessmentsAreKept() {
        Item item = trustedItem("Prune");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assessmentRepo.insert(List.of(
                row(item, now.minusSeconds(8 * 24 * 3600), "NORMAL"),
                row(item, now.minusSeconds(6 * 24 * 3600), "NORMAL")));

        job.runOnce();

        assertThat(countAssessments(item.equipmentId)).isEqualTo(1);
        assertThat(assessmentRepo.points(item.equipmentId, now.minusSeconds(7 * 24 * 3600))).hasSize(1);
    }

    // ----------------------------------------------------------------------------------------------- forecast

    @Test
    void aFreshForecastFillsTheStatusAndAStaleOneIsIgnored() throws Exception {
        Item fresh = trustedItem("ForecastFresh");
        reading(fresh.sensorId, 1, 25.0, 50.0, 20.0);
        forecast(fresh.sensorId, 0, 0.30, true);

        Item stale = trustedItem("ForecastStale");
        reading(stale.sensorId, 1, 25.0, 50.0, 20.0);
        forecast(stale.sensorId, 10, 0.30, true);   // computed ten minutes ago

        JsonNode freshStatus = statusOf(fresh.equipmentId);
        assertThat(freshStatus.get("forecastMargin15C").asDouble()).isEqualTo(3.5);
        assertThat(freshStatus.get("forecastMargin30C").asDouble()).isEqualTo(2.5);
        assertThat(freshStatus.get("forecastMargin60C").asDouble()).isEqualTo(1.25);
        assertThat(freshStatus.get("predictedCondensationAt").asText()).endsWith("Z");

        JsonNode staleStatus = statusOf(stale.equipmentId);
        assertThat(staleStatus.get("forecastMargin15C").isNull()).isTrue();
        assertThat(staleStatus.get("forecastMargin30C").isNull()).isTrue();
        assertThat(staleStatus.get("forecastMargin60C").isNull()).isTrue();
        assertThat(staleStatus.get("predictedCondensationAt").isNull()).isTrue();
    }

    @Test
    void theForecastSignalIsRaisedAtHalfProbabilityAndClearedBelow() {
        Item item = trustedItem("ForecastSignal");
        reading(item.sensorId, 1, 25.0, 50.0, 20.0);   // NORMAL now, so only the forecast signal is in play

        forecast(item.sensorId, 0, 0.49, false);
        job.runOnce();
        assertThat(signalRows(forecastKey(item))).as("below 0.5").isEmpty();

        forecast(item.sensorId, 0, 0.50, false);
        job.runOnce();
        job.runOnce();
        List<Map<String, Object>> rows = signalRows(forecastKey(item));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("state")).isEqualTo("RAISED");
        assertThat(rows.get(0).get("severity")).isEqualTo("WARNING");
        assertThat(rows.get(0).get("signal_type")).isEqualTo("CONDENSATION_FORECAST");
        assertThat(payload(rows.get(0)).get("deviceId").asText()).isEqualTo(item.deviceId);
        assertThat(signalRows(riskKey(item))).as("the risk signal is separate").isEmpty();

        forecast(item.sensorId, 0, 0.10, false);
        job.runOnce();
        rows = signalRows(forecastKey(item));
        assertThat(rows).extracting(r -> (String) r.get("state")).containsExactly("RAISED", "CLEARED");
    }

    @Test
    void aStaleForecastDoesNotRaiseOrClearTheSignal() {
        Item item = trustedItem("ForecastStaleSignal");
        reading(item.sensorId, 1, 25.0, 50.0, 20.0);
        forecast(item.sensorId, 0, 0.9, false);
        job.runOnce();
        assertThat(signalRows(forecastKey(item))).hasSize(1);

        forecast(item.sensorId, 10, 0.1, false);   // an old row now: no decision either way
        job.runOnce();
        assertThat(signalRows(forecastKey(item))).hasSize(1);
    }

    // ----------------------------------------------------------------------------------------------- reports

    @Test
    void theRiskSummaryShowsTheTimeInEachLevelPerEquipmentAndLocation() {
        Item item = trustedItem("Summary");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assessmentRepo.insert(List.of(
                row(item, now.minusSeconds(300), "NORMAL"),
                row(item, now.minusSeconds(240), "NORMAL"),
                row(item, now.minusSeconds(180), "WARNING"),
                row(item, now.minusSeconds(120), "CRITICAL"),
                row(item, now.minusSeconds(60), "CRITICAL")));   // 60 s each, the last one until now

        List<EquipmentRiskSummary> all = analytics.riskSummary(now.minusSeconds(3600), now);
        EquipmentRiskSummary summary = all.stream().filter(s -> s.equipmentId() == item.equipmentId).findFirst()
                .orElseThrow();
        assertThat(summary.normalPct()).isEqualTo(40.0);
        assertThat(summary.warningPct()).isEqualTo(20.0);
        assertThat(summary.criticalPct()).isEqualTo(40.0);
        assertThat(summary.peakRiskIndex()).isEqualTo(0.25);
        assertThat(summary.equipmentName()).startsWith("Rack Summary-");

        List<EquipmentRiskSummary> atLocation = analytics.riskSummary(now.minusSeconds(3600), now,
                summary.locationName());
        assertThat(atLocation).extracting(EquipmentRiskSummary::equipmentId).containsExactly(item.equipmentId);
        assertThat(analytics.riskSummary(now.minusSeconds(3600), now, "Nowhere")).isEmpty();
        assertThat(analytics.riskSummary(now.plusSeconds(3600), null)).as("nothing after now").isEmpty();
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private record Item(long equipmentId, long sensorId, String deviceId) {
    }

    private Item trustedItem(String label) {
        return item(label, true);
    }

    private Item untrustedItem(String label) {
        return item(label, false);
    }

    /** A profile (margin warn 3 / critical 1 C, RH warn 60 / critical 70 %), a location, an equipment item and a sensor. */
    private Item item(String label, boolean trusted) {
        String unique = label + "-" + System.nanoTime() + "-" + (uniqueCounter++);
        jdbc.update("INSERT INTO equipment_profile (name, rh_warn_pct, rh_critical_pct, dew_margin_warn_c, "
                + "dew_margin_critical_c, risk_budget) VALUES (?, 60, 70, 3.0, 1.0, 100)", "Profile " + unique);
        long profileId = jdbc.queryForObject("SELECT id FROM equipment_profile WHERE name = ?", Long.class,
                "Profile " + unique);
        jdbc.update("INSERT INTO location (name, description, profile_id) VALUES (?, 'integration test', ?)",
                "Room " + unique, profileId);
        long locationId = jdbc.queryForObject("SELECT id FROM location WHERE name = ?", Long.class, "Room " + unique);
        jdbc.update("INSERT INTO equipment (location_id, profile_id, name) VALUES (?, ?, ?)", locationId, profileId,
                "Rack " + unique);
        long equipmentId = jdbc.queryForObject("SELECT id FROM equipment WHERE name = ?", Long.class, "Rack " + unique);
        String deviceId = "T-" + unique;
        jdbc.update("INSERT INTO sensors (device_id, equipment_id, secret_enc) VALUES (?, ?, 'test-not-a-secret')",
                deviceId, equipmentId);
        long sensorId = jdbc.queryForObject("SELECT id FROM sensors WHERE device_id = ?", Long.class, deviceId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO sensor_health (sensor_id, status, connectivity, trusted, health_score, issues, "
                        + "status_since, evaluated_at) VALUES (?, ?, 'ONLINE', ?, 100, '[]', ?, ?)",
                sensorId, trusted ? "HEALTHY" : "STUCK", trusted, now, now);
        return new Item(equipmentId, sensorId, deviceId);
    }

    private void reading(long sensorId, int minutesAgo, double tempC, double rhPct, double surfaceTempC) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo);
        jdbc.update("INSERT INTO readings (sensor_id, seq_no, measured_at, received_at, temperature_c, humidity_pct, "
                + "surface_temp_c) VALUES (?, ?, ?, ?, ?, ?, ?)", sensorId, ++seq, at, at, tempC, rhPct, surfaceTempC);
    }

    /** Writes the sensor's forecast row (one per sensor, rewritten each time) as the platform would. */
    private void forecast(long sensorId, int computedMinutesAgo, double probability60, boolean withPredictedTime) {
        LocalDateTime computed = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(computedMinutesAgo);
        jdbc.update("DELETE FROM sensor_forecasts WHERE sensor_id = ?", sensorId);
        jdbc.update("INSERT INTO sensor_forecasts (sensor_id, margin_15_c, margin_30_c, margin_60_c, "
                        + "warning_probability_30, warning_probability_60, method, predicted_condensation_at, computed_at) "
                        + "VALUES (?, 3.5, 2.5, 1.25, 0.2, ?, 'ML', ?, ?)", sensorId, probability60,
                withPredictedTime ? LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20) : null, computed);
    }

    private static AssessmentRepo.Row row(Item item, Instant assessedAt, String level) {
        return new AssessmentRepo.Row(item.equipmentId, assessedAt, assessedAt, 25.0, 20.0, 6.15, 13.85, 50.0, 0.25,
                level);
    }

    private static String riskKey(Item item) {
        return "ANALYTICS:CONDENSATION_RISK:" + item.equipmentId;
    }

    private static String forecastKey(Item item) {
        return "ANALYTICS:CONDENSATION_FORECAST:" + item.equipmentId;
    }

    private List<Map<String, Object>> signalRows(String dedupKey) {
        return jdbc.queryForList("SELECT * FROM alert_signals WHERE dedup_key = ? ORDER BY id", dedupKey);
    }

    private JsonNode payload(Map<String, Object> signalRow) {
        try {
            return json.readTree(String.valueOf(signalRow.get("payload")));
        } catch (Exception e) {
            throw new AssertionError("payload is not JSON: " + signalRow.get("payload"), e);
        }
    }

    private int countAssessments(long equipmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM risk_assessments WHERE equipment_id = ?", Integer.class,
                equipmentId);
    }

    private String signIn() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "operator@corrosionwatch.app",
                                "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + signIn()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode statusOf(long equipmentId) throws Exception {
        for (JsonNode item : getJson("/api/v1/equipment/status")) {
            if (item.get("equipmentId").asLong() == equipmentId) {
                return item;
            }
        }
        throw new AssertionError("equipment " + equipmentId + " is not in the status list");
    }

    private static Set<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return new TreeSet<>(names);
    }
}

package com.team404.corrosion.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team404.corrosion.AivenMySql;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * GET /api/v1/equipment/status end to end: sign in, HTTP, security, the real SQL in JdbcAnalyticsRepo, the maths and
 * the JSON, on MySQL with the production settings (ANSI sql_mode, required primary keys).
 *
 * <p>The platform's tables (sensors, sensor_health, readings) come from the platform's own migration file, run here
 * the same way SharedSchemaMigrationTest does. Every test inserts its own equipment, so the 12 demo items from V3
 * (which have no sensors and therefore report UNKNOWN) do not matter.
 */
@SpringBootTest(properties = {
        "ecp.security.jwt-secret=test-only-jwt-secret-for-backend-tests-0001",
        "ecp.demo.password=" + EquipmentStatusIntegrationTest.PASSWORD,
        "ecp.alerts.scheduler.enabled=false",
        "ecp.audit.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class EquipmentStatusIntegrationTest {

    static final String PASSWORD = "demo-password-for-tests";

    private static final Path PLATFORM_TABLES = Path.of(
            "../platform/ecp-platform-app/src/main/resources/db/platform/V1__platform_tables.sql")
            .toAbsolutePath().normalize();

    /** The fields of EquipmentStatus in frontend/src/api/types.ts. */
    private static final Set<String> TYPE_FIELDS = new TreeSet<>(List.of(
            "equipmentId", "equipmentName", "locationName", "profileName", "profileKind", "riskLevel", "riskIndex",
            "temperatureC", "humidityPct", "surfaceTempC", "dewPointC", "marginC",
            "forecastMargin15C", "forecastMargin30C", "forecastMargin60C", "predictedCondensationAt", "updatedAt",
            "marginWarningC", "marginCriticalC", "rhWarningPct", "rhCriticalPct", "tempMinC", "tempMaxC",
            "surfaceOffsetC", "sensorId", "deviceId", "trusted", "timeOfWetnessMin24h"));

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

    private long uniqueCounter;

    @BeforeEach
    void createPlatformTables() throws Exception {
        // CREATE TABLE IF NOT EXISTS, so running it before every test is harmless
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource(PLATFORM_TABLES));
        }
    }

    // ----------------------------------------------------------------------------------------------- the tests

    @Test
    void trustedSensorGivesTheFullStatusWithCorrectDewPointAndMargin() throws Exception {
        long equipmentId = equipment("Rack", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-TRUSTED");
        sensorHealth(sensorId, true);
        // 25 C at 50 % RH: dew point 13.85 C. Measured surface 20.0 C leaves a margin of 6.15 C.
        for (int minutesAgo : new int[] {3, 2, 1}) {
            reading(sensorId, minutesAgo, 25.0, 50.0, 20.0);
        }

        JsonNode s = statusOf(equipmentId);

        assertThat(fieldNames(s)).isEqualTo(TYPE_FIELDS);
        assertThat(s.get("equipmentName").asText()).startsWith("Rack");
        assertThat(s.get("locationName").asText()).startsWith("Room");
        assertThat(s.get("profileName").asText()).startsWith("Profile");
        assertThat(s.get("riskLevel").asText()).isEqualTo("NORMAL");
        assertThat(s.get("temperatureC").asDouble()).isEqualTo(25.0);
        assertThat(s.get("humidityPct").asDouble()).isEqualTo(50.0);
        assertThat(s.get("surfaceTempC").asDouble()).isEqualTo(20.0);
        assertThat(s.get("dewPointC").asDouble()).isCloseTo(13.85, org.assertj.core.data.Offset.offset(0.02));
        assertThat(s.get("marginC").asDouble()).isCloseTo(6.15, org.assertj.core.data.Offset.offset(0.02));
        assertThat(s.get("riskIndex").asDouble()).isGreaterThan(0.0);
        assertThat(s.get("timeOfWetnessMin24h").asDouble()).isZero();   // 50 % RH is not wet
        assertThat(s.get("trusted").asBoolean()).isTrue();
        assertThat(s.get("sensorId").asLong()).isEqualTo(sensorId);
        // sensor() makes the device id unique by adding a suffix, so only the start is fixed
        assertThat(s.get("deviceId").asText()).startsWith("T-TRUSTED");
        assertThat(s.get("updatedAt").asText()).endsWith("Z");
        // the profile thresholds and the data added in V8 come from the database, not from Java
        assertThat(s.get("marginWarningC").asDouble()).isEqualTo(3.0);
        assertThat(s.get("marginCriticalC").asDouble()).isEqualTo(1.0);
        assertThat(s.get("rhWarningPct").asDouble()).isEqualTo(60.0);
        assertThat(s.get("rhCriticalPct").asDouble()).isEqualTo(70.0);
        assertThat(s.get("profileKind").asText()).isEqualTo("plc");
        assertThat(s.get("tempMinC").asDouble()).isEqualTo(2.0);
        assertThat(s.get("tempMaxC").asDouble()).isEqualTo(45.0);
        assertThat(s.get("surfaceOffsetC").asDouble()).isEqualTo(2.5);
        // not built yet
        assertThat(s.get("forecastMargin15C").isNull()).isTrue();
        assertThat(s.get("predictedCondensationAt").isNull()).isTrue();
    }

    @Test
    void surfaceTemperatureFallsBackToAirMinusTheProfileOffsetFromTheDatabase() throws Exception {
        long equipmentId = equipment("NoSurface", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-NOSURFACE");
        sensorHealth(sensorId, true);
        reading(sensorId, 1, 25.0, 50.0, null);

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("surfaceTempC").asDouble()).isEqualTo(22.5);   // 25 - 2.5 (surface_offset_c of the profile)
    }

    @Test
    void condensingSurfaceIsCritical() throws Exception {
        long equipmentId = equipment("Wet", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-WET");
        sensorHealth(sensorId, true);
        reading(sensorId, 2, 25.0, 90.0, 20.0);   // dew point 23.2 C is above the 20 C surface
        reading(sensorId, 1, 25.0, 90.0, 20.0);

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("marginC").asDouble()).isNegative();
        assertThat(s.get("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(s.get("timeOfWetnessMin24h").asDouble()).isGreaterThan(0.0);   // 90 % RH is wet
    }

    @Test
    void untrustedSensorGivesUnknownAndItsReadingsAreNotUsed() throws Exception {
        long equipmentId = equipment("Untrusted", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-UNTRUSTED");
        sensorHealth(sensorId, false);
        reading(sensorId, 1, 25.0, 90.0, 20.0);   // would be CRITICAL if it were trusted

        JsonNode s = statusOf(equipmentId);

        assertThat(fieldNames(s)).isEqualTo(TYPE_FIELDS);
        assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(s.get("trusted").asBoolean()).isFalse();
        assertThat(s.get("sensorId").asLong()).isEqualTo(sensorId);
        for (String field : List.of("riskIndex", "temperatureC", "humidityPct", "surfaceTempC", "dewPointC",
                "marginC", "updatedAt", "timeOfWetnessMin24h")) {
            assertThat(s.get(field).isNull()).as(field + " is null without a trusted reading").isTrue();
        }
        // the profile data is still reported
        assertThat(s.get("marginCriticalC").asDouble()).isEqualTo(1.0);
        assertThat(s.get("profileKind").asText()).isEqualTo("plc");
    }

    @Test
    void sensorWithoutAHealthRowIsNotTrusted() throws Exception {
        long equipmentId = equipment("NoHealth", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-NOHEALTH");
        reading(sensorId, 1, 25.0, 50.0, 20.0);   // no sensor_health row yet

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(s.get("trusted").asBoolean()).isFalse();
    }

    @Test
    void equipmentWithoutASensorIsUnknown() throws Exception {
        long equipmentId = equipment("NoSensor", 3.0, 1.0, 60.0, 70.0);

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(s.get("sensorId").isNull()).isTrue();
        assertThat(s.get("deviceId").isNull()).isTrue();
        assertThat(s.get("trusted").asBoolean()).isFalse();
    }

    @Test
    void severalSensorsUseATrustedOneNotJustTheFirst() throws Exception {
        long equipmentId = equipment("TwoSensors", 3.0, 1.0, 60.0, 70.0);
        long firstUntrusted = sensor(equipmentId, "T-FIRST");
        long secondTrusted = sensor(equipmentId, "T-SECOND");
        sensorHealth(firstUntrusted, false);
        sensorHealth(secondTrusted, true);
        reading(firstUntrusted, 1, 25.0, 90.0, 20.0);   // bad data from the untrusted sensor
        reading(secondTrusted, 1, 25.0, 50.0, 20.0);

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("sensorId").asLong()).isEqualTo(secondTrusted);
        assertThat(s.get("deviceId").asText()).startsWith("T-SECOND");
        assertThat(s.get("trusted").asBoolean()).isTrue();
        assertThat(s.get("riskLevel").asText()).isEqualTo("NORMAL");
    }

    @Test
    void inactiveSensorIsIgnored() throws Exception {
        long equipmentId = equipment("Inactive", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-INACTIVE");
        jdbc.update("UPDATE sensors SET active = FALSE WHERE id = ?", sensorId);
        sensorHealth(sensorId, true);
        reading(sensorId, 1, 25.0, 50.0, 20.0);

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(s.get("sensorId").isNull()).isTrue();
    }

    @Test
    void readingsOlderThan24HoursAreNotUsed() throws Exception {
        long equipmentId = equipment("Stale", 3.0, 1.0, 60.0, 70.0);
        long sensorId = sensor(equipmentId, "T-STALE");
        sensorHealth(sensorId, true);
        reading(sensorId, 25 * 60, 25.0, 50.0, 20.0);   // 25 hours ago

        JsonNode s = statusOf(equipmentId);

        assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void requiresASignedInUser() throws Exception {
        mvc.perform(get("/api/v1/equipment/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyDemoEquipmentItemIsListed() throws Exception {
        JsonNode all = statusList();
        // V3 seeds 12 equipment items; other tests in this class add more, so there are at least 12
        assertThat(all.size()).isGreaterThanOrEqualTo(12);
        for (JsonNode item : all) {
            assertThat(fieldNames(item)).isEqualTo(TYPE_FIELDS);
        }
    }

    // ----------------------------------------------------------------------------------------------- helpers

    /** Inserts a location, a profile (with the V8 columns set to non-default values) and an equipment item. */
    private long equipment(String label, double marginWarn, double marginCritical, double rhWarn, double rhCritical) {
        String unique = label + "-" + System.nanoTime() + "-" + (uniqueCounter++);
        jdbc.update("INSERT INTO equipment_profile (name, rh_warn_pct, rh_critical_pct, dew_margin_warn_c, "
                        + "dew_margin_critical_c, risk_budget, kind, temp_min_c, temp_max_c, surface_offset_c) "
                        + "VALUES (?, ?, ?, ?, ?, 100, 'plc', 2.0, 45.0, 2.5)",
                "Profile " + unique, rhWarn, rhCritical, marginWarn, marginCritical);
        long profileId = jdbc.queryForObject("SELECT id FROM equipment_profile WHERE name = ?", Long.class,
                "Profile " + unique);
        jdbc.update("INSERT INTO location (name, description, profile_id) VALUES (?, 'integration test', ?)",
                "Room " + unique, profileId);
        long locationId = jdbc.queryForObject("SELECT id FROM location WHERE name = ?", Long.class, "Room " + unique);
        jdbc.update("INSERT INTO equipment (location_id, profile_id, name) VALUES (?, ?, ?)",
                locationId, profileId, "Rack " + unique);
        return jdbc.queryForObject("SELECT id FROM equipment WHERE name = ?", Long.class, "Rack " + unique);
    }

    /** Inserts a platform sensor (sensors.secret_enc is NOT NULL; the value is irrelevant here). */
    private long sensor(long equipmentId, String deviceId) {
        String unique = deviceId + "-" + System.nanoTime();
        jdbc.update("INSERT INTO sensors (device_id, equipment_id, secret_enc) VALUES (?, ?, 'test-not-a-secret')",
                unique, equipmentId);
        return jdbc.queryForObject("SELECT id FROM sensors WHERE device_id = ?", Long.class, unique);
    }

    private void sensorHealth(long sensorId, boolean trusted) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO sensor_health (sensor_id, status, connectivity, trusted, health_score, issues, "
                        + "status_since, evaluated_at) VALUES (?, ?, 'ONLINE', ?, 100, '[]', ?, ?)",
                sensorId, trusted ? "HEALTHY" : "STUCK", trusted, now, now);
    }

    private int seq;

    private void reading(long sensorId, int minutesAgo, double tempC, double rhPct, Double surfaceTempC) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo);
        jdbc.update("INSERT INTO readings (sensor_id, seq_no, measured_at, received_at, temperature_c, humidity_pct, "
                        + "surface_temp_c) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sensorId, ++seq, at, at, tempC, rhPct, surfaceTempC);
    }

    private String signIn() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "operator@corrosionwatch.app",
                                "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private JsonNode statusList() throws Exception {
        String body = mvc.perform(get("/api/v1/equipment/status").header("Authorization", "Bearer " + signIn()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode all = json.readTree(body);
        assertThat(all.isArray()).isTrue();
        return all;
    }

    private JsonNode statusOf(long equipmentId) throws Exception {
        for (JsonNode item : statusList()) {
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

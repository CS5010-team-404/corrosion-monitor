package edu.neu.ecp.platform.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import edu.neu.ecp.health.infra.HealthRepository;
import edu.neu.ecp.platform.MySqlIntegrationTest;
import edu.neu.ecp.platform.demo.EmbeddedSimulator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FR1: PATCH /api/v1/admin/sensors/{id} edits the expected reporting interval. Real security (JWT, ADMIN only), real
 * SQL, the audit outbox, sensor health picking up the new value, and the simulator being told.
 */
@AutoConfigureMockMvc
class SensorIntervalIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HealthRepository healthRepository;

    @MockitoSpyBean
    EmbeddedSimulator simulator;

    private static String bearer(String email, String role) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(
                new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(email).issuedAt(now).expiresAt(now.plusSeconds(600))
                        .claim("roles", List.of(role)).build())).getTokenValue();
    }

    private static final String ADMIN = "admin@corrosionwatch.app";

    private static MockHttpServletRequestBuilder setInterval(long id, String body) {
        return patch("/api/v1/admin/sensors/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void adminChangesTheIntervalAndItIsAuditedAppliedAndUsedByHealth() throws Exception {
        long id = sensor("INT-EDIT", true, 15, Instant.now());
        String deviceId = deviceId(id);

        mvc.perform(setInterval(id, "{\"expectedIntervalS\":30}").header("Authorization", bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.deviceId").value(deviceId))
                .andExpect(jsonPath("$.expectedIntervalS").value(30))
                .andExpect(jsonPath("$.secretEnc").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT expected_interval_s FROM sensors WHERE id = ?", Integer.class, id))
                .isEqualTo(30);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_type, actor, entity_type, JSON_EXTRACT(details, '$.fromSeconds') AS from_s,
                       JSON_EXTRACT(details, '$.toSeconds') AS to_s
                FROM audit_outbox WHERE action = 'SENSOR_INTERVAL_CHANGED' AND entity_id = ?
                """, deviceId);
        assertThat(audit).containsEntry("actor_type", "USER").containsEntry("actor", ADMIN)
                .containsEntry("entity_type", "sensor");
        assertThat(audit.get("from_s").toString()).isEqualTo("15");
        assertThat(audit.get("to_s").toString()).isEqualTo("30");
        verify(simulator).setInterval(deviceId, Duration.ofSeconds(30));
        // sensor health judges LATE/OFFLINE against the new interval from its next pass
        assertThat(healthRepository.activeSensors()).filteredOn(s -> s.sensorId() == id).singleElement()
                .satisfies(s -> assertThat(s.expectedInterval()).isEqualTo(Duration.ofSeconds(30)));
        // and the registry list the UI reads shows it
        mvc.perform(get("/api/v1/sensors").header("Authorization", bearer("tech@corrosionwatch.app", "TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].expectedIntervalS").value(30));
    }

    @Test
    void sameValueChangesNothingAndWritesNoAudit() throws Exception {
        long id = sensor("INT-SAME", true, 15, Instant.now());
        mvc.perform(setInterval(id, "{\"expectedIntervalS\":15}").header("Authorization", bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedIntervalS").value(15));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_outbox WHERE entity_id = ?", Integer.class,
                deviceId(id))).isZero();
        verify(simulator, never()).setInterval(deviceId(id), Duration.ofSeconds(15));
    }

    @Test
    void boundsAreFiveSecondsToOneHour() throws Exception {
        long id = sensor("INT-BOUNDS", true, 15, Instant.now());
        String admin = bearer(ADMIN, "ADMIN");
        for (String bad : List.of("{\"expectedIntervalS\":4}", "{\"expectedIntervalS\":3601}", "{\"expectedIntervalS\":-1}",
                "{\"expectedIntervalS\":null}", "{}")) {
            mvc.perform(setInterval(id, bad).header("Authorization", admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(setInterval(id, "{\"expectedIntervalS\":\"often\"}").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(setInterval(id, "{\"expectedIntervalS\":5}").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(setInterval(id, "{\"expectedIntervalS\":3600}").header("Authorization", admin))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT expected_interval_s FROM sensors WHERE id = ?", Integer.class, id))
                .isEqualTo(3600);
    }

    @Test
    void onlyAdminsMayChangeIt() throws Exception {
        long id = sensor("INT-ROLES", true, 15, Instant.now());
        for (String role : List.of("TECHNICIAN", "OPERATOR", "MANAGER")) {
            mvc.perform(setInterval(id, "{\"expectedIntervalS\":60}").header("Authorization", bearer("x@y.z", role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(setInterval(id, "{\"expectedIntervalS\":60}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT expected_interval_s FROM sensors WHERE id = ?", Integer.class, id))
                .isEqualTo(15);
        verify(simulator, never()).setInterval(anyString(), any());
    }

    @Test
    void unknownSensorIsNotFound() throws Exception {
        mvc.perform(setInterval(987_654_321L, "{\"expectedIntervalS\":60}").header("Authorization", bearer(ADMIN, "ADMIN")))
                .andExpect(status().isNotFound());
    }
}

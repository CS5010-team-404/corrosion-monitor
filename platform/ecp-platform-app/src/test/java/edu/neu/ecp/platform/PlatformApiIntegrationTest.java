package edu.neu.ecp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import edu.neu.ecp.health.infra.SensorHealthService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The platform's user-facing APIs with the real SecurityConfig and the backend's JWTs: who may call what, and that
 * health, devices, simulator control and forecasts return real data from MySQL.
 */
class PlatformApiIntegrationTest extends ApiTestSupport {

    @Autowired
    SensorHealthService health;

    /** A registered device with ten fresh readings, one a minute. */
    private Device deviceWithReadings(String prefix) throws Exception {
        Device d = register(newDeviceId(prefix), 1);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String[] readings = new String[10];
        for (int i = 0; i < 10; i++) {
            readings[i] = reading(i + 1, now.minusSeconds(60L * (9 - i)), 22.0 + i * 0.1, 50.0 + i * 0.5);
        }
        ingest(d.deviceId(), d.secret(), batch(readings), now).andExpect(status().isAccepted());
        return d;
    }

    // ---- health ---------------------------------------------------------------------------------------------------

    @Test
    void healthNeedsSignInAndShowsEveryEvaluatedSensor() throws Exception {
        Device d = deviceWithReadings("HEALTH");
        health.evaluateAll();
        mvc.perform(get("/api/v1/health/sensors")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/health/sensors").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].deviceId", hasItem(d.deviceId())));
        mvc.perform(get("/api/v1/health/sensors/" + d.id()).header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensor.deviceId").value(d.deviceId()))
                .andExpect(jsonPath("$.sensor.connectivity").value("ONLINE"))
                .andExpect(jsonPath("$.sensor.lastSeqNo").value(10));
        for (String path : new String[] {"/api/v1/health/summary", "/api/v1/health/events", "/api/v1/health/services"}) {
            mvc.perform(get(path).header("Authorization", bearer("TECHNICIAN"))).andExpect(status().isOk());
        }
    }

    // ---- devices --------------------------------------------------------------------------------------------------

    @Test
    void onlyAdminsRegisterDevicesAndTheSecretIsNeverListed() throws Exception {
        String id = newDeviceId("REG");
        String body = "{\"deviceId\":\"" + id + "\",\"equipmentId\":2}";
        mvc.perform(post("/api/v1/admin/sensors").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/sensors").header("Authorization", bearer("TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        Device d = register(id, 2);
        assertThat(d.secret()).hasSize(43);
        mvc.perform(post("/api/v1/admin/sensors").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/sensors").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"no spaces!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/sensors").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].deviceId", hasItem(id)))
                .andExpect(jsonPath("$[0]", not(hasKey("secret_enc"))))
                .andExpect(jsonPath("$[0]", not(hasKey("secretEnc"))));
        assertThat(jdbc.queryForObject("SELECT secret_enc FROM sensors WHERE device_id = ?", String.class, id)).doesNotContain(d.secret());
        assertThat(count("SELECT COUNT(*) FROM audit_outbox WHERE action = 'SENSOR_REGISTERED' AND entity_id = ? AND actor = 'admin@corrosionwatch.app'", id)).isEqualTo(1);
    }

    @Test
    void rotatingTheSecretStopsTheOldOneImmediately() throws Exception {
        Device d = register(newDeviceId("ROT"), 3);
        mvc.perform(post("/api/v1/admin/sensors/" + d.deviceId() + "/rotate-secret").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isForbidden());
        String res = mvc.perform(post("/api/v1/admin/sensors/" + d.deviceId() + "/rotate-secret").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fresh = json.readTree(res).get("secret").asText();
        assertThat(fresh).isNotEqualTo(d.secret());
        Instant now = Instant.now();
        ingest(d.deviceId(), d.secret(), batch(reading(1, now, 22, 50)), now)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.outcome").value("BAD_SIGNATURE"));
        ingest(d.deviceId(), fresh, batch(reading(1, now, 22, 50)), now).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/admin/sensors/NO-SUCH-DEVICE/rotate-secret").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM audit_outbox WHERE action = 'SENSOR_SECRET_ROTATED' AND entity_id = ?", d.deviceId())).isEqualTo(1);
    }

    // ---- simulator control (the embedded simulator is off here; SimulatorControlTest covers a running one) ------------

    @Test
    void simulatorControlIsAdminOnly() throws Exception {
        mvc.perform(get("/api/v1/admin/simulator")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/simulator").header("Authorization", bearer("TECHNICIAN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/simulator").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/admin/simulator/scenarios").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("STUCK")))
                .andExpect(jsonPath("$.length()").value(10));
        mvc.perform(post("/api/v1/admin/simulator/SIM-001/scenario").header("Authorization", bearer("TECHNICIAN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"STUCK\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/simulator/SIM-001/scenario").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"MELTDOWN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/simulator/SIM-001/scenario").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"STUCK\"}"))
                .andExpect(status().isNotFound());   // no simulator running, so no simulated device
    }

    // ---- forecast -------------------------------------------------------------------------------------------------

    @Test
    void forecastFromRecentReadings() throws Exception {
        Device d = deviceWithReadings("FC");
        mvc.perform(get("/api/v1/sensors/" + d.id() + "/forecast")).andExpect(status().isUnauthorized());
        String res = mvc.perform(get("/api/v1/sensors/" + d.id() + "/forecast").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode f = json.readTree(res);
        assertThat(f.get("margin15C").isNumber()).isTrue();
        assertThat(f.get("margin60C").isNumber()).isTrue();
        assertThat(f.get("risk60").asDouble()).isBetween(0.0, 1.0);
        assertThat(f.get("method").asText()).isIn("model", "trend");
        Device silent = register(newDeviceId("FC0"), 1);
        mvc.perform(get("/api/v1/sensors/" + silent.id() + "/forecast").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/forecast/model").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNotEmpty());
    }

    @Test
    void unknownPathsAreDeniedAndHealthCheckIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/internal/anything").header("Authorization", bearer("ADMIN"))).andExpect(status().isForbidden());
    }
}

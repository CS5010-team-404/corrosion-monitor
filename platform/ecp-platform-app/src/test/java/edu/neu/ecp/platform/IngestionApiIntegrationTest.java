package edu.neu.ecp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.neu.ecp.common.domain.Hmac;
import edu.neu.ecp.ingestion.ReplayGuard;
import edu.neu.ecp.ingestion.SensorRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * POST /api/v1/ingest/readings end to end: HMAC request signing, the timestamp window, validation, storage in
 * readings (once per device sequence number) and every rejection recorded in ingest_rejections.
 */
class IngestionApiIntegrationTest extends ApiTestSupport {

    @Autowired
    SensorRegistry registry;

    @Autowired
    ReplayGuard replays;

    private Device device;
    private Instant now;

    @BeforeEach
    void device() throws Exception {
        device = register(newDeviceId("ING"), 1);
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private Map<String, Object> lastRejection(String deviceId) {
        return jdbc.queryForMap("SELECT reason, sensor_id, detail FROM ingest_rejections WHERE device_id = ? ORDER BY id DESC LIMIT 1", deviceId);
    }

    private long stored() {
        return count("SELECT COUNT(*) FROM readings WHERE sensor_id = ?", device.id());
    }

    @Test
    void validSignedBatchIsStored() throws Exception {
        String body = batch(reading(1, now.minusSeconds(30), 22.4, 48.2), reading(2, now.minusSeconds(15), 22.5, 48.0),
                reading(3, now, 22.6, 47.9));
        ingest(device.deviceId(), device.secret(), body, now)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.duplicates").value(0));
        assertThat(stored()).isEqualTo(3);
        Map<String, Object> row = jdbc.queryForMap("SELECT temperature_c, humidity_pct, surface_temp_c FROM readings WHERE sensor_id = ? AND seq_no = 1", device.id());
        assertThat(((Number) row.get("temperature_c")).doubleValue()).isEqualTo(22.4);
        assertThat(row.get("surface_temp_c")).isNull();
    }

    @Test
    void anIdenticalRequestIsRefusedAsAReplayAndOverlappingRetriesAreStoredOnce() throws Exception {
        String body = batch(reading(1, now.minusSeconds(30), 22.4, 48.2), reading(2, now.minusSeconds(15), 22.5, 48.0));
        ingest(device.deviceId(), device.secret(), body, now).andExpect(status().isAccepted());
        // The identical signed request again (a replay inside the 5-minute window): refused and logged.
        ingest(device.deviceId(), device.secret(), body, now)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("REPLAYED_REQUEST"));
        assertThat(lastRejection(device.deviceId())).containsEntry("reason", "REPLAYED_REQUEST")
                .containsEntry("sensor_id", device.id()).containsEntry("detail", "identical signed request already accepted");
        // A retry signed again a second later that overlaps: accepted, and only the new sequence number is stored.
        ingest(device.deviceId(), device.secret(), batch(reading(2, now.minusSeconds(15), 22.5, 48.0), reading(3, now, 22.6, 47.9)),
                now.plusSeconds(1))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
        assertThat(stored()).isEqualTo(3);
    }

    private org.springframework.test.web.servlet.ResultActions ingestWithNonce(String body, Instant signedAt, String nonce)
            throws Exception {
        long ts = signedAt.getEpochSecond();
        return mvc.perform(post("/api/v1/ingest/readings").header("X-Device-Id", device.deviceId())
                .header("X-Timestamp", String.valueOf(ts)).header("X-Nonce", nonce)
                .header("X-Signature", Hmac.signRequest(device.secret(), ts, nonce, body))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aNonceIsAcceptedOnceEvenWithANewBodyAndTimestamp() throws Exception {
        String nonce = Hmac.newNonce();
        ingestWithNonce(batch(reading(1, now, 22.4, 48.2)), now, nonce).andExpect(status().isAccepted());
        // same nonce, correctly signed, different body and timestamp: still a replay of the nonce
        ingestWithNonce(batch(reading(2, now, 22.5, 48.2)), now.plusSeconds(2), nonce)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("REPLAYED_REQUEST"));
        assertThat(lastRejection(device.deviceId())).containsEntry("detail", "nonce already used");
        // a fresh nonce for the same body is fine
        ingestWithNonce(batch(reading(2, now, 22.5, 48.2)), now.plusSeconds(2), Hmac.newNonce()).andExpect(status().isAccepted());
        assertThat(stored()).isEqualTo(2);
    }

    @Test
    void replayGuardKeysExpireAndArePruned() {
        jdbc.update("INSERT INTO ingest_replay_guard (sensor_id, request_key, expires_at) VALUES (?, ?, ?)",
                device.id(), "e".repeat(64), java.sql.Timestamp.from(now.minus(Duration.ofMinutes(1))));
        jdbc.update("INSERT INTO ingest_replay_guard (sensor_id, request_key, expires_at) VALUES (?, ?, ?)",
                device.id(), "f".repeat(64), java.sql.Timestamp.from(now.plus(Duration.ofMinutes(5))));
        // an expired key that has not been pruned yet is taken over, a live one is not
        assertThat(replays.claim(device.id(), "e".repeat(64), now.plus(Duration.ofMinutes(6)), now)).isTrue();
        assertThat(replays.claim(device.id(), "f".repeat(64), now.plus(Duration.ofMinutes(6)), now)).isFalse();
        jdbc.update("UPDATE ingest_replay_guard SET expires_at = ? WHERE sensor_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofDays(1))), device.id());
        assertThat(replays.prune()).isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ingest_replay_guard WHERE sensor_id = ?", device.id())).isZero();
    }

    @Test
    void replayOutsideTheWindowIsRejectedAsStale() throws Exception {
        Instant old = now.minus(Duration.ofMinutes(10));
        ingest(device.deviceId(), device.secret(), batch(reading(1, old, 22.4, 48.2)), old)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.outcome").value("STALE_TIMESTAMP"));
        assertThat(lastRejection(device.deviceId())).containsEntry("reason", "STALE_TIMESTAMP").containsEntry("sensor_id", device.id());
        assertThat(stored()).isZero();
    }

    @Test
    void badSignatureIsRejectedAndRecorded() throws Exception {
        String body = batch(reading(1, now, 22.4, 48.2));
        ingest(device.deviceId(), "not-the-device-secret", body, now)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.outcome").value("BAD_SIGNATURE"));
        // Signed correctly, then the body changed in transit.
        long ts = now.getEpochSecond();
        mvc.perform(post("/api/v1/ingest/readings").header("X-Device-Id", device.deviceId()).header("X-Timestamp", String.valueOf(ts))
                        .header("X-Signature", Hmac.signRequest(device.secret(), ts, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("22.4", "12.4")))
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT COUNT(*) FROM ingest_rejections WHERE device_id = ? AND reason = 'BAD_SIGNATURE'", device.deviceId())).isEqualTo(2);
        assertThat(stored()).isZero();
    }

    @Test
    void unknownDeviceIsRejectedAndRecorded() throws Exception {
        String unknown = newDeviceId("GHOST");
        ingest(unknown, "whatever", batch(reading(1, now, 22.4, 48.2)), now)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.outcome").value("UNKNOWN_DEVICE"));
        Map<String, Object> r = lastRejection(unknown);
        assertThat(r.get("reason")).isEqualTo("UNKNOWN_DEVICE");
        assertThat(r.get("sensor_id")).isNull();
    }

    @Test
    void deactivatedSensorIsForbidden() throws Exception {
        jdbc.update("UPDATE sensors SET active = FALSE WHERE id = ?", device.id());
        registry.evict(device.deviceId());
        ingest(device.deviceId(), device.secret(), batch(reading(1, now, 22.4, 48.2)), now)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.outcome").value("INACTIVE_SENSOR"));
        assertThat(lastRejection(device.deviceId())).containsEntry("reason", "INACTIVE_SENSOR");
    }

    @Test
    void invalidValuesAreRejectedWithTheReasonAndNothingIsStored() throws Exception {
        String[][] cases = {
                {batch(reading(1, now, 1e9, 48.2)), "temperatureC and humidityPct must be numbers"},
                {"{\"readings\":[]}", "readings must be a non-empty array"},
                {batch(reading(1, now.plus(Duration.ofMinutes(10)), 22.4, 48.2)), "measuredAt is in the future"},
                {"{\"readings\":[{\"seq\":1,\"temperatureC\":22.4,\"humidityPct\":48.2}]}", "measuredAt is required (ISO-8601 UTC)"},
                {"{\"readings\": [", "body is not valid JSON"},
        };
        for (String[] c : cases) {
            ingest(device.deviceId(), device.secret(), c[0], now)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.outcome").value("INVALID_PAYLOAD"))
                    .andExpect(jsonPath("$.detail").value(c[1]));
            assertThat(lastRejection(device.deviceId())).containsEntry("reason", "INVALID_PAYLOAD").containsEntry("detail", c[1]);
        }
        assertThat(stored()).isZero();
    }

    @Test
    void impossibleButNumericValuesAreKeptForSensorHealth() throws Exception {
        ingest(device.deviceId(), device.secret(), batch(reading(1, now, 22.4, 104.0)), now).andExpect(status().isAccepted());
        assertThat(stored()).isEqualTo(1);
    }

    @Test
    void missingSignatureHeadersAre400() throws Exception {
        mvc.perform(post("/api/v1/ingest/readings").header("X-Device-Id", device.deviceId())
                        .contentType(MediaType.APPLICATION_JSON).content(batch(reading(1, now, 22.4, 48.2))))
                .andExpect(status().isBadRequest());
        assertThat(stored()).isZero();
    }
}

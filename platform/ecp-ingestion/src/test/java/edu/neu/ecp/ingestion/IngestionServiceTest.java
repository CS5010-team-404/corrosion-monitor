package edu.neu.ecp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.neu.ecp.common.domain.Hmac;
import edu.neu.ecp.common.domain.Topics;
import edu.neu.ecp.common.live.LiveUpdates;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ingestion rules without a database: the order of the checks, the status and reason of each rejection, that
 * every rejection is recorded in ingest_rejections, and how duplicates are counted. The SQL itself runs against
 * MySQL in ecp-platform-app's IngestionApiIntegrationTest.
 */
class IngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final String SECRET = "device-secret-for-tests";
    private static final String BODY = """
            {"readings":[{"seq":1,"measuredAt":"2026-11-20T11:59:30Z","temperatureC":22.4,"humidityPct":48.2,"surfaceTempC":20.9},
                         {"seq":2,"measuredAt":"2026-11-20T11:59:45Z","temperatureC":22.5,"humidityPct":48.0}]}""";

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private SensorRegistry registry;
    private JdbcTemplate jdbc;
    private LiveUpdates live;
    private IngestionService service;
    private ReplayGuard replays;

    @BeforeEach
    void setUp() {
        registry = mock(SensorRegistry.class);
        jdbc = mock(JdbcTemplate.class);
        live = mock(LiveUpdates.class);
        replays = mock(ReplayGuard.class);
        when(replays.claim(anyLong(), anyString(), any(), any())).thenReturn(true);
        service = new IngestionService(registry, jdbc, json, live, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5),
                replays);
        when(registry.find("SIM-001")).thenReturn(Optional.of(new SensorRegistry.RegisteredSensor(7, "SIM-001", 3, true, SECRET)));
        when(registry.find("OFF-001")).thenReturn(Optional.of(new SensorRegistry.RegisteredSensor(8, "OFF-001", 3, false, SECRET)));
        when(registry.find("NOPE")).thenReturn(Optional.empty());
    }

    private static String ts(Instant t) {
        return String.valueOf(t.getEpochSecond());
    }

    private static String sign(String body, Instant t) {
        return Hmac.signRequest(SECRET, t.getEpochSecond(), body);
    }

    /** Checks the rejection was written to ingest_rejections with this reason and sensor id. */
    private void assertRecorded(String device, Long sensorId, String reason) {
        verify(jdbc).update(anyString(), eq(device), eq(sensorId), eq(reason), anyString(), any());
    }

    @Test
    void acceptsASignedBatchAndPublishesTheLatestReading() {
        when(jdbc.update(startsWith("INSERT IGNORE"), any(Object[].class))).thenReturn(2);
        var r = service.ingest("SIM-001", ts(NOW), sign(BODY, NOW), BODY);
        assertThat(r.httpStatus()).isEqualTo(202);
        assertThat(r.outcome()).isEqualTo("ACCEPTED");
        assertThat(r.accepted()).isEqualTo(2);
        assertThat(r.duplicates()).isZero();
        verify(live).publish(eq(Topics.READINGS), any());
    }

    @Test
    void countsReadingsAlreadyStoredAsDuplicates() {
        when(jdbc.update(startsWith("INSERT IGNORE"), any(Object[].class))).thenReturn(1);   // one row already stored
        var r = service.ingest("SIM-001", ts(NOW), sign(BODY, NOW), BODY);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.duplicates()).isEqualTo(1);
    }

    @Test
    void unknownDeviceIs401AndNothingElseIsChecked() {
        var r = service.ingest("NOPE", ts(NOW), "whatever", BODY);
        assertThat(r.httpStatus()).isEqualTo(401);
        assertThat(r.outcome()).isEqualTo("UNKNOWN_DEVICE");
        assertRecorded("NOPE", null, "UNKNOWN_DEVICE");
        verify(jdbc, never()).update(startsWith("INSERT IGNORE"), any(Object[].class));
    }

    @Test
    void deactivatedSensorIs403() {
        var r = service.ingest("OFF-001", ts(NOW), sign(BODY, NOW), BODY);
        assertThat(r.httpStatus()).isEqualTo(403);
        assertThat(r.outcome()).isEqualTo("INACTIVE_SENSOR");
        assertRecorded("OFF-001", 8L, "INACTIVE_SENSOR");
    }

    @Test
    void timestampOutsideTheWindowIs401EvenWithAGoodSignature() {
        Instant old = NOW.minus(Duration.ofMinutes(6));
        var r = service.ingest("SIM-001", ts(old), sign(BODY, old), BODY);
        assertThat(r.httpStatus()).isEqualTo(401);
        assertThat(r.outcome()).isEqualTo("STALE_TIMESTAMP");
        assertRecorded("SIM-001", 7L, "STALE_TIMESTAMP");
        Instant future = NOW.plus(Duration.ofMinutes(6));
        assertThat(service.ingest("SIM-001", ts(future), sign(BODY, future), BODY).outcome()).isEqualTo("STALE_TIMESTAMP");
    }

    @Test
    void nonNumericTimestampIs400() {
        var r = service.ingest("SIM-001", "yesterday", sign(BODY, NOW), BODY);
        assertThat(r.httpStatus()).isEqualTo(400);
        assertThat(r.outcome()).isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    void wrongSignatureOrChangedBodyIs401() {
        assertThat(service.ingest("SIM-001", ts(NOW), "00".repeat(32), BODY).outcome()).isEqualTo("BAD_SIGNATURE");
        String tampered = BODY.replace("22.4", "12.4");
        var r = service.ingest("SIM-001", ts(NOW), sign(BODY, NOW), tampered);
        assertThat(r.httpStatus()).isEqualTo(401);
        assertThat(r.outcome()).isEqualTo("BAD_SIGNATURE");
        verify(jdbc, never()).update(startsWith("INSERT IGNORE"), any(Object[].class));
    }

    @Test
    void signedButMalformedJsonIs400() {
        String body = "{\"readings\": [";
        var r = service.ingest("SIM-001", ts(NOW), sign(body, NOW), body);
        assertThat(r.httpStatus()).isEqualTo(400);
        assertThat(r.detail()).isEqualTo("body is not valid JSON");
    }

    @Test
    void invalidValuesAreRejectedWithTheReason() {
        String garbage = "{\"readings\":[{\"seq\":1,\"measuredAt\":\"2026-11-20T11:59:30Z\",\"temperatureC\":1e9,\"humidityPct\":48}]}";
        var r = service.ingest("SIM-001", ts(NOW), sign(garbage, NOW), garbage);
        assertThat(r.httpStatus()).isEqualTo(400);
        assertThat(r.detail()).isEqualTo("temperatureC and humidityPct must be numbers");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), eq("SIM-001"), eq(7L), eq("INVALID_PAYLOAD"), detail.capture(), any());
        assertThat(detail.getValue()).isEqualTo("temperatureC and humidityPct must be numbers");
    }

    @Test
    void validationRules() {
        var ok = new IngestionService.ReadingIn(1L, NOW, 22.0, 50.0, null);
        assertThat(IngestionService.validate(new IngestionService.Payload(List.of(ok)), NOW)).isNull();
        assertThat(IngestionService.validate(null, NOW)).contains("non-empty");
        assertThat(IngestionService.validate(new IngestionService.Payload(List.of()), NOW)).contains("non-empty");
        assertThat(IngestionService.validate(new IngestionService.Payload(
                java.util.Collections.nCopies(IngestionService.MAX_BATCH + 1, ok)), NOW)).contains("at most 500");
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(-1L, NOW, 22.0, 50.0, null)), NOW)).contains("seq");
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(1L, null, 22.0, 50.0, null)), NOW)).contains("measuredAt is required");
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(1L, NOW.plusSeconds(301), 22.0, 50.0, null)), NOW)).contains("future");
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(1L, NOW, Double.NaN, 50.0, null)), NOW)).contains("must be numbers");
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(1L, NOW, 22.0, null, null)), NOW)).contains("must be numbers");
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(1L, NOW, 22.0, 50.0, 400.0)), NOW)).contains("surfaceTempC");
        // Physically impossible but plausible numbers are kept, so sensor health can flag a failing sensor.
        assertThat(IngestionService.validate(payload(new IngestionService.ReadingIn(1L, NOW, 22.0, 104.0, null)), NOW)).isNull();
    }

    // ---------------------------------------------------------------- replay protection

    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsARequestSignedWithANonceAndRemembersTheNonceUntilTheWindowCloses() {
        when(jdbc.update(startsWith("INSERT IGNORE"), any(Object[].class))).thenReturn(2);
        String sig = Hmac.signRequest(SECRET, NOW.getEpochSecond(), NONCE, BODY);
        var r = service.ingest("SIM-001", ts(NOW), sig, NONCE, BODY);
        assertThat(r.httpStatus()).isEqualTo(202);
        // remembered for the 5-minute timestamp window plus a 1-minute margin, keyed by the nonce, not the signature
        verify(replays).claim(7L, ReplayGuard.key(NONCE, sig), NOW.plusSeconds(360), NOW);
        assertThat(ReplayGuard.key(NONCE, sig)).isNotEqualTo(ReplayGuard.key(null, sig));
    }

    @Test
    void theNonceIsPartOfTheSignature() {
        String legacySig = sign(BODY, NOW); // signed without the nonce, then a nonce added on the way
        var r = service.ingest("SIM-001", ts(NOW), legacySig, NONCE, BODY);
        assertThat(r.httpStatus()).isEqualTo(401);
        assertThat(r.outcome()).isEqualTo("BAD_SIGNATURE");
        var swapped = service.ingest("SIM-001", ts(NOW), Hmac.signRequest(SECRET, NOW.getEpochSecond(), NONCE, BODY),
                "ffffffffffffffffffffffffffffffff", BODY);
        assertThat(swapped.outcome()).isEqualTo("BAD_SIGNATURE");
    }

    @Test
    void aMalformedNonceIs400() {
        var r = service.ingest("SIM-001", ts(NOW), "x", "short", BODY);
        assertThat(r.httpStatus()).isEqualTo(400);
        assertThat(r.detail()).contains("X-Nonce");
        assertRecorded("SIM-001", 7L, "INVALID_PAYLOAD");
    }

    @Test
    void aReplayIs409AndLoggedAndStoresNothing() {
        when(replays.claim(anyLong(), anyString(), any(), any())).thenReturn(false);
        var r = service.ingest("SIM-001", ts(NOW), sign(BODY, NOW), BODY);
        assertThat(r.httpStatus()).isEqualTo(409);
        assertThat(r.outcome()).isEqualTo("REPLAYED_REQUEST");
        assertRecorded("SIM-001", 7L, "REPLAYED_REQUEST");
        verify(jdbc, never()).update(startsWith("INSERT IGNORE"), any(Object[].class));
        verify(live, never()).publish(any(), any());
    }

    @Test
    void legacyRequestsAreKeyedByTheirSignature() {
        when(jdbc.update(startsWith("INSERT IGNORE"), any(Object[].class))).thenReturn(2);
        String sig = sign(BODY, NOW);
        service.ingest("SIM-001", ts(NOW), sig.toUpperCase(), BODY);
        verify(replays).claim(7L, ReplayGuard.key(null, sig), NOW.plusSeconds(360), NOW);
    }

    @Test
    void rejectedRequestsNeverClaimAKey() {
        service.ingest("SIM-001", ts(NOW), "00".repeat(32), BODY);
        service.ingest("SIM-001", ts(NOW.minusSeconds(3600)), sign(BODY, NOW.minusSeconds(3600)), BODY);
        verify(replays, never()).claim(anyLong(), anyString(), any(), any());
    }

    private static IngestionService.Payload payload(IngestionService.ReadingIn r) {
        return new IngestionService.Payload(List.of(r));
    }
}

package edu.neu.ecp.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.neu.ecp.common.domain.Hmac;
import edu.neu.ecp.common.domain.Topics;
import edu.neu.ecp.common.live.LiveUpdates;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Verifies, validates and stores device readings. Order of checks: known device, active, fresh timestamp,
 * well-formed nonce, valid signature, valid payload, not a replay. Every rejection is recorded in ingest_rejections.
 *
 * <p>Replays: a signed request is accepted once. Its nonce (or, for devices that send none, its signature) is
 * remembered until its timestamp leaves the window ({@link ReplayGuard}); the same request again gets 409
 * REPLAYED_REQUEST. A device that retries after a timeout should sign again (new timestamp and nonce); readings it
 * already delivered are still stored once, by (sensor, seq).
 *
 * <p>Impossible values (RH 104 %) are stored on purpose: rejecting them would hide a failing sensor.
 */
@Service
public class IngestionService {

    public static final int MAX_BATCH = 500;
    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);
    /** Kept a little longer than the timestamp window, so no replayable request is ever forgotten. */
    static final Duration GUARD_MARGIN = Duration.ofMinutes(1);
    private static final java.util.regex.Pattern NONCE = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{16,64}");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReadingIn(Long seq, Instant measuredAt, Double temperatureC, Double humidityPct, Double surfaceTempC) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(List<ReadingIn> readings) {
    }

    public record Result(int httpStatus, String outcome, int accepted, int duplicates, String detail) {
        static Result rejected(int status, String reason, String detail) {
            return new Result(status, reason, 0, 0, detail);
        }
    }

    private final SensorRegistry registry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LiveUpdates live;
    private final Clock clock;
    private final long maxClockSkewSeconds;
    private final ReplayGuard replays;

    public IngestionService(SensorRegistry registry, JdbcTemplate jdbc, ObjectMapper json, LiveUpdates live, Clock clock,
                            @Value("${ecp.ingestion.max-clock-skew:PT5M}") Duration maxClockSkew, ReplayGuard replays) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.json = json;
        this.live = live;
        this.clock = clock;
        this.maxClockSkewSeconds = maxClockSkew.toSeconds();
        this.replays = replays;
    }

    /** A legacy request without a nonce. */
    public Result ingest(String deviceId, String timestampHeader, String signature, String body) {
        return ingest(deviceId, timestampHeader, signature, null, body);
    }

    /** @param nonce the X-Nonce header, or null for a legacy device */
    public Result ingest(String deviceId, String timestampHeader, String signature, String nonce, String body) {
        Instant now = clock.instant();
        var sensor = registry.find(deviceId);
        if (sensor.isEmpty()) {
            return reject(deviceId, null, now, 401, "UNKNOWN_DEVICE", "device is not registered");
        }
        var s = sensor.get();
        if (!s.active()) {
            return reject(deviceId, s.id(), now, 403, "INACTIVE_SENSOR", "sensor is deactivated");
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return reject(deviceId, s.id(), now, 400, "INVALID_PAYLOAD", "X-Timestamp must be epoch seconds");
        }
        if (Math.abs(now.getEpochSecond() - ts) > maxClockSkewSeconds) {
            return reject(deviceId, s.id(), now, 401, "STALE_TIMESTAMP", "X-Timestamp outside allowed window");
        }
        if (nonce != null && !NONCE.matcher(nonce).matches()) {
            return reject(deviceId, s.id(), now, 400, "INVALID_PAYLOAD", "X-Nonce must be 16-64 of A-Z a-z 0-9 _ -");
        }
        if (!Hmac.verifyRequest(s.secret(), ts, nonce, body, signature)) {
            return reject(deviceId, s.id(), now, 401, "BAD_SIGNATURE", "signature does not match");
        }

        Payload payload;
        try {
            payload = json.readValue(body, Payload.class);
        } catch (Exception e) {
            return reject(deviceId, s.id(), now, 400, "INVALID_PAYLOAD", "body is not valid JSON");
        }
        String problem = validate(payload, now);
        if (problem != null) {
            return reject(deviceId, s.id(), now, 400, "INVALID_PAYLOAD", problem);
        }

        Instant expires = Instant.ofEpochSecond(ts).plusSeconds(maxClockSkewSeconds).plus(GUARD_MARGIN);
        if (!replays.claim(s.id(), ReplayGuard.key(nonce, signature), expires, now)) {
            return reject(deviceId, s.id(), now, 409, "REPLAYED_REQUEST",
                    nonce != null ? "nonce already used" : "identical signed request already accepted");
        }
        int accepted = insert(s.id(), payload.readings(), now);
        int duplicates = payload.readings().size() - accepted;
        ReadingIn last = payload.readings().get(payload.readings().size() - 1);
        if (payload.readings().size() <= 5) { // skip UI pushes during load tests
            live.publish(Topics.READINGS, Map.of("sensorId", s.id(), "equipmentId", s.equipmentId(),
                    "measuredAt", last.measuredAt(), "temperatureC", last.temperatureC(),
                    "humidityPct", last.humidityPct()));
        }
        return new Result(202, "ACCEPTED", accepted, duplicates, null);
    }

    static String validate(Payload p, Instant now) {
        if (p == null || p.readings() == null || p.readings().isEmpty()) {
            return "readings must be a non-empty array";
        }
        if (p.readings().size() > MAX_BATCH) {
            return "at most " + MAX_BATCH + " readings per request";
        }
        for (ReadingIn r : p.readings()) {
            if (r.seq() == null || r.seq() < 0) {
                return "seq must be a non-negative integer";
            }
            if (r.measuredAt() == null) {
                return "measuredAt is required (ISO-8601 UTC)";
            }
            if (r.measuredAt().isAfter(now.plus(MAX_FUTURE))) {
                return "measuredAt is in the future";
            }
            if (!plausibleNumber(r.temperatureC()) || !plausibleNumber(r.humidityPct())) {
                return "temperatureC and humidityPct must be numbers";
            }
            if (r.surfaceTempC() != null && !plausibleNumber(r.surfaceTempC())) {
                return "surfaceTempC must be a number";
            }
        }
        return null;
    }

    /** Rejects garbage (NaN, 1e9) but keeps physically impossible values for health to flag. */
    private static boolean plausibleNumber(Double v) {
        return v != null && Double.isFinite(v) && v > -200 && v < 300;
    }

    /**
     * Stores the batch with one multi-row INSERT IGNORE and returns how many rows were new. A (sensor, seq) already
     * stored is skipped by the unique key, so a replayed or retried batch is stored once. The affected-row count of one
     * statement is exact; per-row batch counts are not (with rewriteBatchedStatements the driver reports
     * SUCCESS_NO_INFO for every row, which used to count duplicates as accepted).
     */
    private int insert(long sensorId, List<ReadingIn> readings, Instant receivedAt) {
        Timestamp received = Timestamp.from(receivedAt);
        StringBuilder sql = new StringBuilder("""
                INSERT IGNORE INTO readings
                  (sensor_id, seq_no, measured_at, received_at, temperature_c, humidity_pct, surface_temp_c)
                VALUES """);
        List<Object> args = new ArrayList<>(readings.size() * 7);
        for (int i = 0; i < readings.size(); i++) {
            ReadingIn r = readings.get(i);
            sql.append(i == 0 ? " " : ", ").append("(?, ?, ?, ?, ?, ?, ?)");
            args.add(sensorId);
            args.add(r.seq());
            args.add(Timestamp.from(r.measuredAt()));
            args.add(received);
            args.add(r.temperatureC());
            args.add(r.humidityPct());
            args.add(r.surfaceTempC());
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    private Result reject(String deviceId, Long sensorId, Instant now, int status, String reason, String detail) {
        jdbc.update("""
                INSERT INTO ingest_rejections (device_id, sensor_id, reason, detail, received_at) VALUES (?, ?, ?, ?, ?)
                """, truncate(deviceId, 64), sensorId, reason, detail, Timestamp.from(now));
        return Result.rejected(status, reason, detail);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

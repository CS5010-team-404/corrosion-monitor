package edu.neu.ecp.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Cached device lookup so a 500-readings-per-second burst does not hit the sensors table every request. */
@Component
public class SensorRegistry {

    public record RegisteredSensor(long id, String deviceId, long equipmentId, boolean active, String secret) {
    }

    private record Cached(Optional<RegisteredSensor> sensor, Instant loadedAt) {
    }

    private static final Duration TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final SensorSecretCipher cipher;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SensorRegistry(JdbcTemplate jdbc, SensorSecretCipher cipher, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.clock = clock;
    }

    public Optional<RegisteredSensor> find(String deviceId) {
        Instant now = clock.instant();
        Cached c = cache.get(deviceId);
        if (c != null && Duration.between(c.loadedAt(), now).compareTo(TTL) < 0) {
            return c.sensor();
        }
        List<RegisteredSensor> rows = jdbc.query(
                "SELECT id, device_id, equipment_id, active, secret_enc FROM sensors WHERE device_id = ?",
                (rs, i) -> new RegisteredSensor(rs.getLong("id"), rs.getString("device_id"), rs.getLong("equipment_id"),
                        rs.getBoolean("active"), cipher.decrypt(rs.getString("secret_enc"))), deviceId);
        Optional<RegisteredSensor> found = rows.stream().findFirst();
        cache.put(deviceId, new Cached(found, now));
        return found;
    }

    /** Call after an admin creates, rotates or deactivates a sensor. */
    public void evict(String deviceId) {
        cache.remove(deviceId);
    }
}

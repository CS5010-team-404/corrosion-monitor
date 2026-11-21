package edu.neu.ecp.platform.devices;

import edu.neu.ecp.common.audit.AuditEvent;
import edu.neu.ecp.common.audit.AuditRecorder;
import edu.neu.ecp.ingestion.SensorRegistry;
import edu.neu.ecp.ingestion.SensorSecretCipher;
import edu.neu.ecp.platform.demo.EmbeddedSimulator;
import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device registry. A device's HMAC key is generated here, stored encrypted, and returned exactly once.
 * equipmentId/equipmentName/locationName refer to the team backend's equipment; names are copied here so the
 * platform never needs to join the team's tables.
 */
@RestController
public class DeviceController {

    public record RegisterRequest(String deviceId, Long equipmentId, String equipmentName, String locationName,
                                  String kind, String model, Integer expectedIntervalS) {
    }

    public record Registered(String deviceId, String secret, String note) {
    }

    /** PATCH body. Only the reporting interval can be changed for now (FR1). */
    public record UpdateRequest(Integer expectedIntervalS) {
    }

    // never return secret_enc; keys match Device in frontend/src/api/types.ts
    private static final String DEVICE_COLUMNS = """
            SELECT id, device_id AS deviceId, kind, model, expected_interval_s AS expectedIntervalS, active,
                   equipment_id AS equipmentId, equipment_name AS equipmentName,
                   location_name AS locationName, created_at AS createdAt
            FROM sensors
            """;

    private final JdbcTemplate jdbc;
    private final SensorSecretCipher cipher;
    private final SensorRegistry registry;
    private final AuditRecorder audit;
    private final EmbeddedSimulator simulator;
    private final TransactionTemplate tx;

    public DeviceController(JdbcTemplate jdbc, SensorSecretCipher cipher, SensorRegistry registry, AuditRecorder audit,
                            EmbeddedSimulator simulator, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.registry = registry;
        this.audit = audit;
        this.simulator = simulator;
        this.tx = tx;
    }

    @GetMapping("/api/v1/sensors")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(DEVICE_COLUMNS + " ORDER BY device_id");
    }

    /**
     * Changes a sensor's expected reporting interval (admin only, via the /api/v1/admin/** rule). Sensor health judges
     * LATE/OFFLINE against the new value from its next pass, and a simulated device switches to it at once; a physical
     * device has to be set to the same interval on the device itself.
     */
    @PatchMapping("/api/v1/admin/sensors/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody UpdateRequest r, Principal principal) {
        Integer interval = r.expectedIntervalS();
        ReportingInterval.problem(interval).ifPresent(message -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        });
        Map<String, Object> before = find(id);
        String deviceId = (String) before.get("deviceId");
        int previous = ((Number) before.get("expectedIntervalS")).intValue();
        if (previous != interval) {
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE sensors SET expected_interval_s = ? WHERE id = ?", interval, id);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("sensorId", id);
                details.put("fromSeconds", previous);
                details.put("toSeconds", interval);
                audit.record(AuditEvent.user(name(principal), "SENSOR_INTERVAL_CHANGED", "sensor", deviceId, details));
            });
            simulator.setInterval(deviceId, Duration.ofSeconds(interval));
        }
        return find(id);
    }

    private Map<String, Object> find(long id) {
        return jdbc.queryForList(DEVICE_COLUMNS + " WHERE id = ?", id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No sensor " + id));
    }

    @PostMapping("/api/v1/admin/sensors")
    public Registered register(@RequestBody RegisterRequest r, Principal principal) {
        if (r.deviceId() == null || !r.deviceId().matches("[A-Za-z0-9_-]{3,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId: 3-64 letters, digits, - or _");
        }
        String kind = r.kind() == null ? "PHYSICAL" : r.kind().toUpperCase();
        String secret = SensorSecretCipher.newSecret();
        try {
            jdbc.update("""
                    INSERT INTO sensors (device_id, equipment_id, equipment_name, location_name, kind, model,
                                         secret_enc, expected_interval_s)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, r.deviceId(), r.equipmentId(), r.equipmentName(), r.locationName(), kind,
                    r.model() == null ? "SHT31" : r.model(), cipher.encrypt(secret),
                    r.expectedIntervalS() == null ? 15 : r.expectedIntervalS());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device id already exists");
        }
        registry.evict(r.deviceId());
        audit.record(AuditEvent.user(name(principal), "SENSOR_REGISTERED", "sensor", r.deviceId(),
                Map.of("kind", kind)));
        return new Registered(r.deviceId(), secret, "Store this secret on the device now. It will not be shown again.");
    }

    @PostMapping("/api/v1/admin/sensors/{deviceId}/rotate-secret")
    public Registered rotate(@PathVariable String deviceId, Principal principal) {
        String secret = SensorSecretCipher.newSecret();
        if (jdbc.update("UPDATE sensors SET secret_enc = ? WHERE device_id = ?", cipher.encrypt(secret), deviceId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No sensor " + deviceId);
        }
        registry.evict(deviceId);
        audit.record(AuditEvent.user(name(principal), "SENSOR_SECRET_ROTATED", "sensor", deviceId, Map.of()));
        return new Registered(deviceId, secret, "The old secret stops working immediately.");
    }

    static String name(Principal p) {
        return p == null ? "dev" : p.getName();
    }
}

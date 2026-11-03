package edu.neu.ecp.platform.devices;

import edu.neu.ecp.common.audit.AuditEvent;
import edu.neu.ecp.common.audit.AuditRecorder;
import edu.neu.ecp.ingestion.SensorRegistry;
import edu.neu.ecp.ingestion.SensorSecretCipher;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final JdbcTemplate jdbc;
    private final SensorSecretCipher cipher;
    private final SensorRegistry registry;
    private final AuditRecorder audit;

    public DeviceController(JdbcTemplate jdbc, SensorSecretCipher cipher, SensorRegistry registry, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.registry = registry;
        this.audit = audit;
    }

    @GetMapping("/api/sensors")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT id, device_id, kind, model, expected_interval_s, active, equipment_id, equipment_name,
                       location_name, created_at
                FROM sensors ORDER BY device_id
                """); // never return secret_enc
    }

    @PostMapping("/api/admin/sensors")
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

    @PostMapping("/api/admin/sensors/{deviceId}/rotate-secret")
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

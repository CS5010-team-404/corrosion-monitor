package edu.neu.ecp.platform.demo;

import edu.neu.ecp.ingestion.SensorSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registers twelve simulated devices on an empty database so the demo works on day one. Equipment ids 1-12 are
 * placeholders: point them at the team's real equipment rows with a one-line UPDATE (docs/ARCHITECTURE.md).
 * Each device also carries the room conditions its simulator should start from. Turn off with ECP_DEMO_SEED=false.
 */
@Component
@Order(1)
public class DemoDevices implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDevices.class);

    //               device       equipment              location                 temp  rh   surface offset
    private static final Object[][] FLEET = {
        {"SIM-001", "Rack A1", "Server Room A", 22.4, 45, 1.5}, {"SIM-002", "Rack A2", "Server Room A", 19.6, 56, 1.5},
        {"SIM-003", "Rack A3", "Server Room A", 21.8, 47, 1.5}, {"SIM-004", "Core switch cabinet", "Telecom Closet 2", 24.1, 49, 2.0},
        {"SIM-005", "Patch panel C2", "Telecom Closet 2", 23.2, 52, 2.0}, {"SIM-006", "Router cabinet", "Telecom Closet 2", 25.0, 46, 2.0},
        {"SIM-007", "PLC panel L3-01", "Factory Floor, Line 3", 27.3, 55, 2.5}, {"SIM-008", "Drive panel L3-02", "Factory Floor, Line 3", 29.0, 52, 2.5},
        {"SIM-009", "PLC panel L3-03", "Factory Floor, Line 3", 26.1, 57, 2.5}, {"SIM-010", "Kiosk N1", "North Gate", 13.2, 68, 3.0},
        {"SIM-011", "Kiosk N2", "North Gate", 14.0, 64, 3.0}, {"SIM-012", "Barrier controller", "North Gate", 12.6, 62, 3.0},
    };

    private final JdbcTemplate jdbc;
    private final SensorSecretCipher cipher;
    private final boolean enabled;

    public DemoDevices(JdbcTemplate jdbc, SensorSecretCipher cipher, @Value("${ecp.demo.seed:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sensors", Integer.class);
        if (!enabled || count == null || count > 0) {
            return;
        }
        long equipmentId = 1;
        for (Object[] d : FLEET) {
            jdbc.update("""
                    INSERT INTO sensors (device_id, equipment_id, equipment_name, location_name, kind, model, secret_enc,
                                         expected_interval_s, sim_base_temp_c, sim_base_rh_pct, sim_surface_offset_c)
                    VALUES (?, ?, ?, ?, 'SIMULATED', 'SIM-SHT31', ?, 15, ?, ?, ?)
                    """, d[0], equipmentId++, d[1], d[2], cipher.encrypt(SensorSecretCipher.newSecret()), d[3], d[4], d[5]);
        }
        LOG.info("Registered {} simulated demo devices", FLEET.length);
    }
}

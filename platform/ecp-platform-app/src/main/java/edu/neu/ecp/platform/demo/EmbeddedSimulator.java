package edu.neu.ecp.platform.demo;

import edu.neu.ecp.ingestion.SensorSecretCipher;
import edu.neu.ecp.simulator.HttpReadingSender;
import edu.neu.ecp.simulator.Scenario;
import edu.neu.ecp.simulator.SimulatedSensor;
import edu.neu.ecp.simulator.SimulatorEngine;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs the simulated devices inside this service for the demo. Readings still go through the real HTTP ingestion
 * endpoint with real signatures, so the whole pipeline is exercised. ECP_SIMULATOR_ENABLED=false turns it off
 * (for example when the standalone simulator container or a Raspberry Pi sends instead).
 */
@Component
public class EmbeddedSimulator {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedSimulator.class);

    private final JdbcTemplate jdbc;
    private final SensorSecretCipher cipher;
    private final Clock clock;
    private final boolean enabled;
    private final int port;
    private final double speed;
    private volatile SimulatorEngine engine;

    public EmbeddedSimulator(JdbcTemplate jdbc, SensorSecretCipher cipher, Clock clock,
                             @Value("${ecp.simulator.enabled:true}") boolean enabled,
                             @Value("${server.port:8081}") int port,
                             @Value("${ecp.simulator.scenario-speed:1.0}") double speed) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.clock = clock;
        this.enabled = enabled;
        this.port = port;
        this.speed = speed;
    }

    private record Dev(long id, String deviceId, String secretEnc, int intervalS, double temp, double rh, double offset) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        List<Dev> devices = jdbc.query("""
                SELECT id, device_id, secret_enc, expected_interval_s, COALESCE(sim_base_temp_c, 22) AS t,
                       COALESCE(sim_base_rh_pct, 48) AS rh, COALESCE(sim_surface_offset_c, 1.5) AS off
                FROM sensors WHERE kind = 'SIMULATED' AND active = TRUE ORDER BY id
                """, (rs, i) -> new Dev(rs.getLong("id"), rs.getString("device_id"), rs.getString("secret_enc"),
                rs.getInt("expected_interval_s"), rs.getDouble("t"), rs.getDouble("rh"), rs.getDouble("off")));
        if (devices.isEmpty()) {
            return;
        }
        SimulatorEngine e = new SimulatorEngine(new HttpReadingSender("http://localhost:" + port + "/api/ingest/readings"), clock, 2);
        for (Dev d : devices) {
            long lastSeq = Optional.ofNullable(jdbc.queryForObject("SELECT MAX(seq_no) FROM readings WHERE sensor_id = ?",
                    Long.class, d.id())).orElse(0L);
            e.add(new SimulatedSensor(d.deviceId(), cipher.decrypt(d.secretEnc()), d.temp(), d.rh(), d.offset(),
                    d.id() * 7919L, lastSeq).withScenarioSpeed(speed), Duration.ofSeconds(d.intervalS()));
        }
        engine = e;
        LOG.info("Embedded simulator started for {} devices", devices.size());
    }

    public Optional<SimulatorEngine> engine() {
        return Optional.ofNullable(engine);
    }

    public boolean setScenario(String deviceId, Scenario scenario) {
        return engine().map(x -> x.setScenario(deviceId, scenario)).orElse(false);
    }

    @PreDestroy
    public void stop() {
        if (engine != null) {
            engine.close();
        }
    }
}

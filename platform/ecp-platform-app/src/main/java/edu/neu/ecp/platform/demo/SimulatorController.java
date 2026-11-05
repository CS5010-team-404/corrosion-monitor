package edu.neu.ecp.platform.demo;

import edu.neu.ecp.common.audit.AuditEvent;
import edu.neu.ecp.common.audit.AuditRecorder;
import edu.neu.ecp.simulator.Scenario;
import edu.neu.ecp.simulator.SimulatorEngine;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Administration → Simulator: switch a simulated device into a failure during the demo. */
@RestController
@RequestMapping("/api/v1/admin/simulator")
public class SimulatorController {

    public record ScenarioRequest(String scenario) {
    }

    public record ScenarioOption(String name, String description) {
    }

    private final EmbeddedSimulator simulator;
    private final AuditRecorder audit;

    public SimulatorController(EmbeddedSimulator simulator, AuditRecorder audit) {
        this.simulator = simulator;
        this.audit = audit;
    }

    @GetMapping
    public List<SimulatorEngine.Status> status() {
        return simulator.engine().map(SimulatorEngine::status).orElse(List.of());
    }

    @GetMapping("/scenarios")
    public List<ScenarioOption> scenarios() {
        return Arrays.stream(Scenario.values()).map(s -> new ScenarioOption(s.name(), s.description())).toList();
    }

    @PostMapping("/{deviceId}/scenario")
    public Map<String, String> set(@PathVariable String deviceId, @RequestBody ScenarioRequest body, Principal principal) {
        Scenario scenario;
        try {
            scenario = Scenario.valueOf(body.scenario().toUpperCase());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scenario " + body.scenario());
        }
        if (!simulator.setScenario(deviceId, scenario)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No simulated device " + deviceId);
        }
        audit.record(AuditEvent.user(principal == null ? "dev" : principal.getName(), "SIMULATOR_SCENARIO_SET", "sensor",
                deviceId, Map.of("scenario", scenario.name())));
        return Map.of("deviceId", deviceId, "scenario", scenario.name());
    }
}

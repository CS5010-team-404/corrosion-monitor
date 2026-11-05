package edu.neu.ecp.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.health.domain.EpisodeTracker;
import edu.neu.ecp.health.domain.HealthAssessment;
import edu.neu.ecp.health.domain.HealthEvaluator;
import edu.neu.ecp.health.domain.HealthIssue;
import edu.neu.ecp.health.domain.HealthPolicy;
import edu.neu.ecp.health.domain.HealthStatus;
import edu.neu.ecp.health.domain.Observation;
import edu.neu.ecp.health.domain.ReadingSample;
import edu.neu.ecp.health.domain.SensorRef;
import edu.neu.ecp.simulator.Scenario;
import edu.neu.ecp.simulator.SimulatedReading;
import edu.neu.ecp.simulator.SimulatedSensor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check without a database: every simulator failure scenario must be caught by the health rules,
 * and the normal scenario must stay healthy. This is the demo script, proven in code.
 */
class SimulatorToHealthTest {

    private static final Duration STEP = Duration.ofSeconds(15);
    private static final Instant T0 = Instant.parse("2026-11-02T08:00:00Z");

    @Test
    void normalStaysHealthy() {
        assertEquals(HealthStatus.HEALTHY, runScenario(Scenario.NORMAL, 80).status());
    }

    @Test
    void silentBecomesOffline() {
        HealthAssessment a = runScenario(Scenario.SILENT, 20);
        assertEquals(HealthStatus.OFFLINE, a.status());
        assertTrue(a.activeIssues().contains(HealthIssue.OFFLINE));
    }

    @Test
    void stuckIsCaught() {
        assertEquals(HealthStatus.STUCK, runScenario(Scenario.STUCK, 30).status());
    }

    @Test
    void impossibleAndErraticValuesAreFaulty() {
        assertEquals(HealthStatus.FAULTY, runScenario(Scenario.OUT_OF_RANGE, 30).status());
        assertEquals(HealthStatus.FAULTY, runScenario(Scenario.ERRATIC, 40).status());
    }

    @Test
    void lossyAndSkewedAreDegradedButStillTrusted() {
        HealthAssessment lossy = runScenario(Scenario.LOSSY, 60);
        assertTrue(lossy.activeIssues().contains(HealthIssue.LOSSY), lossy.activeIssues().toString());
        assertTrue(lossy.trusted());
        HealthAssessment skew = runScenario(Scenario.CLOCK_SKEW, 30);
        assertEquals(Set.of(HealthIssue.CLOCK_SKEW), skew.activeIssues());
        assertEquals(HealthStatus.DEGRADED, skew.status());
    }

    /** 20 minutes of normal data, then {@code ticks} ticks of the scenario, evaluating after every tick. */
    private static HealthAssessment runScenario(Scenario scenario, int ticks) {
        HealthPolicy policy = HealthPolicy.defaults();
        HealthEvaluator evaluator = new HealthEvaluator(policy);
        EpisodeTracker tracker = new EpisodeTracker(policy.recoveryEvaluations(), Set.of());
        SimulatedSensor sim = new SimulatedSensor("SIM-001", "k", 22.0, 48.0, 1.5, 7L, 0);
        SensorRef ref = new SensorRef(1L, "SIM-001", 1L, STEP, T0.minus(Duration.ofHours(1)));
        List<ReadingSample> stored = new ArrayList<>();
        Instant lastSeen = null;
        HealthAssessment latest = null;

        int warmup = 80;
        for (int i = 0; i < warmup + ticks; i++) {
            Instant now = T0.plus(STEP.multipliedBy(i));
            if (i == warmup) {
                sim.setScenario(scenario, now);
            }
            var reading = sim.next(now);
            if (reading.isPresent()) {
                SimulatedReading r = reading.get();
                Instant received = now.plusMillis(120);
                stored.add(new ReadingSample(r.seq(), r.measuredAt(), received, r.temperatureC(), r.humidityPct()));
                lastSeen = received;
            }
            Instant evalAt = now.plusSeconds(1);
            Instant windowStart = evalAt.minus(policy.window());
            List<ReadingSample> window = stored.stream().filter(s -> s.receivedAt().isAfter(windowStart)).toList();
            var evaluation = evaluator.evaluate(new Observation(ref, window, lastSeen, 0, evalAt));
            var transition = tracker.apply(evaluation.detected());
            latest = HealthAssessment.from(evaluation, transition.active());
        }
        return latest;
    }
}

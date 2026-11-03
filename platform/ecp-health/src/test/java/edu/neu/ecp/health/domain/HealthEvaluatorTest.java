package edu.neu.ecp.health.domain;

import static edu.neu.ecp.health.domain.Readings.NOW;
import static edu.neu.ecp.health.domain.Readings.SENSOR;
import static edu.neu.ecp.health.domain.Readings.normal;
import static edu.neu.ecp.health.domain.Readings.observe;
import static edu.neu.ecp.health.domain.Readings.series;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthEvaluatorTest {

    private final HealthEvaluator evaluator = new HealthEvaluator(HealthPolicy.defaults());

    @Test
    void steadyVaryingSensorIsHealthyAndOnline() {
        Evaluation e = evaluator.evaluate(observe(normal(40, NOW.minusSeconds(5))));
        assertEquals(Connectivity.ONLINE, e.connectivity());
        assertTrue(e.detected().isEmpty(), "expected no issues but got " + e.detected());
        assertEquals(40, e.metrics().readings());
    }

    @Test
    void overdueSensorIsLateThenOffline() {
        // 15 s interval: late after 30 s, offline after 90 s.
        Evaluation late = evaluator.evaluate(observe(normal(10, NOW.minusSeconds(45))));
        assertEquals(Connectivity.LATE, late.connectivity());
        assertFalse(late.detected().contains(HealthIssue.OFFLINE));

        Evaluation offline = evaluator.evaluate(observe(normal(10, NOW.minusSeconds(120))));
        assertEquals(Connectivity.OFFLINE, offline.connectivity());
        assertTrue(offline.detected().contains(HealthIssue.OFFLINE));
        assertEquals(false, offline.evidence().get(HealthIssue.OFFLINE).get("neverReported"));
    }

    @Test
    void newSensorIsNeverSeenUntilItIsOverdue() {
        SensorRef fresh = new SensorRef(9L, "SIM-009", 1L, Duration.ofSeconds(15), NOW.minusSeconds(30));
        Evaluation e = evaluator.evaluate(new Observation(fresh, List.of(), null, 0, NOW));
        assertEquals(Connectivity.NEVER_SEEN, e.connectivity());
        assertTrue(e.detected().isEmpty());

        SensorRef old = new SensorRef(9L, "SIM-009", 1L, Duration.ofSeconds(15), NOW.minusSeconds(600));
        Evaluation overdue = evaluator.evaluate(new Observation(old, List.of(), null, 0, NOW));
        assertTrue(overdue.detected().contains(HealthIssue.OFFLINE));
        assertEquals(true, overdue.evidence().get(HealthIssue.OFFLINE).get("neverReported"));
    }

    @Test
    void identicalReadingsAreStuck() {
        List<ReadingSample> window = new ArrayList<>(normal(10, NOW.minusSeconds(320)));
        window.addAll(series(20, NOW.minusSeconds(5), i -> new double[] {23.41, 51.20}));
        Evaluation e = evaluator.evaluate(observe(renumber(window)));
        assertTrue(e.detected().contains(HealthIssue.STUCK));
        assertEquals(20, e.evidence().get(HealthIssue.STUCK).get("identicalReadings"));
    }

    @Test
    void nineteenIdenticalReadingsAreNotYetStuck() {
        List<ReadingSample> window = new ArrayList<>(normal(10, NOW.minusSeconds(305)));
        window.addAll(series(19, NOW.minusSeconds(5), i -> new double[] {23.41, 51.20}));
        assertFalse(evaluator.evaluate(observe(renumber(window))).detected().contains(HealthIssue.STUCK));
    }

    @Test
    void impossibleValuesAreOutOfRange() {
        List<ReadingSample> window = series(20, NOW.minusSeconds(5),
                i -> i % 7 == 3 ? new double[] {22.0, 104.0} : new double[] {22.0 + i * 0.01, 50.0 + i * 0.05});
        Evaluation e = evaluator.evaluate(observe(window));
        assertTrue(e.detected().contains(HealthIssue.OUT_OF_RANGE));
        assertEquals(3, e.metrics().outOfRange());
        // Out-of-range points must not also be double-counted as spikes.
        assertFalse(e.detected().contains(HealthIssue.ERRATIC));
    }

    @Test
    void repeatedJumpsAreErratic() {
        List<ReadingSample> window = series(20, NOW.minusSeconds(5),
                i -> i % 4 == 0 ? new double[] {31.0, 48.0} : new double[] {22.0 + i * 0.01, 48.0});
        Evaluation e = evaluator.evaluate(observe(window));
        assertTrue(e.detected().contains(HealthIssue.ERRATIC), "spikes=" + e.metrics().spikes());
    }

    @Test
    void sequenceGapsAreLossy() {
        List<ReadingSample> all = normal(40, NOW.minusSeconds(5));
        List<ReadingSample> kept = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (i % 3 != 1) { // drop a third
                kept.add(all.get(i));
            }
        }
        Evaluation e = evaluator.evaluate(observe(kept));
        assertTrue(e.detected().contains(HealthIssue.LOSSY));
        assertTrue(e.metrics().missingPct() >= 30.0, "missing " + e.metrics().missingPct());
    }

    @Test
    void deviceRestartDoesNotLookLikeLoss() {
        List<ReadingSample> before = normal(20, NOW.minusSeconds(310));
        List<ReadingSample> after = new ArrayList<>();
        Instant start = NOW.minusSeconds(290);
        for (int i = 0; i < 20; i++) { // sequence restarts at 1 after a reboot
            Instant t = start.plusSeconds(15L * i);
            after.add(new ReadingSample(1 + i, t, t.plusMillis(150), 22.0 + 0.01 * i, 47.0 + 0.02 * i));
        }
        List<ReadingSample> window = new ArrayList<>(before);
        window.addAll(after);
        Evaluation e = evaluator.evaluate(observe(window));
        assertFalse(e.detected().contains(HealthIssue.LOSSY), "missing " + e.metrics().missingPct());
        assertEquals(0.0, e.metrics().missingPct());
    }

    @Test
    void repeatedBadSignaturesAreFlagged() {
        List<ReadingSample> window = normal(30, NOW.minusSeconds(5));
        Observation o = new Observation(SENSOR, window, window.get(window.size() - 1).receivedAt(), 3, NOW);
        assertTrue(evaluator.evaluate(o).detected().contains(HealthIssue.SIGNATURE_FAILURES));
    }

    @Test
    void deviceClockFarBehindIsSkewed() {
        List<ReadingSample> window = new ArrayList<>();
        for (ReadingSample r : normal(20, NOW.minusSeconds(5))) {
            window.add(new ReadingSample(r.seqNo(), r.measuredAt().minus(Duration.ofMinutes(10)), r.receivedAt(),
                    r.temperatureC(), r.humidityPct()));
        }
        Evaluation e = evaluator.evaluate(observe(window));
        assertTrue(e.detected().contains(HealthIssue.CLOCK_SKEW));
        assertEquals(600L, e.metrics().medianSkewSeconds());
    }

    private static List<ReadingSample> renumber(List<ReadingSample> window) {
        List<ReadingSample> out = new ArrayList<>();
        for (int i = 0; i < window.size(); i++) {
            ReadingSample r = window.get(i);
            out.add(new ReadingSample(1000 + i, r.measuredAt(), r.receivedAt(), r.temperatureC(), r.humidityPct()));
        }
        return out;
    }
}

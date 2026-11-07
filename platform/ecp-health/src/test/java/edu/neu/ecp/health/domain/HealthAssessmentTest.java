package edu.neu.ecp.health.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HealthAssessmentTest {

    @Test
    void worstIssueWinsTheStatus() {
        assertEquals(HealthStatus.OFFLINE, HealthAssessment.statusOf(Connectivity.OFFLINE,
                Set.of(HealthIssue.OFFLINE, HealthIssue.STUCK)));
        assertEquals(HealthStatus.STUCK, HealthAssessment.statusOf(Connectivity.ONLINE,
                Set.of(HealthIssue.STUCK, HealthIssue.LOSSY)));
        assertEquals(HealthStatus.FAULTY, HealthAssessment.statusOf(Connectivity.ONLINE,
                Set.of(HealthIssue.OUT_OF_RANGE)));
        assertEquals(HealthStatus.DEGRADED, HealthAssessment.statusOf(Connectivity.ONLINE,
                Set.of(HealthIssue.CLOCK_SKEW)));
        assertEquals(HealthStatus.DEGRADED, HealthAssessment.statusOf(Connectivity.LATE, Set.of()));
        assertEquals(HealthStatus.HEALTHY, HealthAssessment.statusOf(Connectivity.ONLINE, Set.of()));
        assertEquals(HealthStatus.UNKNOWN, HealthAssessment.statusOf(Connectivity.NEVER_SEEN, Set.of()));
    }

    @Test
    void scoreDropsWithEachIssueAndNeverGoesNegative() {
        assertEquals(100, HealthAssessment.scoreOf(Connectivity.ONLINE, Set.of()));
        assertEquals(75, HealthAssessment.scoreOf(Connectivity.ONLINE, Set.of(HealthIssue.LOSSY)));
        assertEquals(65, HealthAssessment.scoreOf(Connectivity.LATE, Set.of(HealthIssue.LOSSY)));
        assertEquals(0, HealthAssessment.scoreOf(Connectivity.OFFLINE, Set.of(HealthIssue.OFFLINE, HealthIssue.STUCK)));
    }

    @Test
    void analyticsMustIgnoreStuckButMayUseLossySensors() {
        Evaluation e = new HealthEvaluator(HealthPolicy.defaults())
                .evaluate(Readings.observe(Readings.normal(30, Readings.NOW.minusSeconds(5))));
        assertFalse(HealthAssessment.from(e, Set.of(HealthIssue.STUCK)).trusted());
        assertTrue(HealthAssessment.from(e, Set.of(HealthIssue.LOSSY)).trusted());
        assertTrue(HealthAssessment.from(e, Set.of()).trusted());
    }
}

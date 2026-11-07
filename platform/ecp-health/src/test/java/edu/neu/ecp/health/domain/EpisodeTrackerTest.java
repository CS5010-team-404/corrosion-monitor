package edu.neu.ecp.health.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class EpisodeTrackerTest {

    @Test
    void opensOnFirstDetectionAndClosesAfterThreeCleanEvaluations() {
        EpisodeTracker tracker = new EpisodeTracker(3, Set.of());

        EpisodeTracker.Transition t1 = tracker.apply(Set.of(HealthIssue.STUCK));
        assertEquals(Set.of(HealthIssue.STUCK), t1.opened());

        assertFalse(tracker.apply(Set.of()).changed());
        assertFalse(tracker.apply(Set.of()).changed());
        EpisodeTracker.Transition t4 = tracker.apply(Set.of());
        assertEquals(Set.of(HealthIssue.STUCK), t4.closed());
        assertTrue(t4.active().isEmpty());
    }

    @Test
    void redetectionResetsTheCleanStreak() {
        EpisodeTracker tracker = new EpisodeTracker(3, Set.of());
        tracker.apply(Set.of(HealthIssue.ERRATIC));
        tracker.apply(Set.of());
        tracker.apply(Set.of());
        tracker.apply(Set.of(HealthIssue.ERRATIC)); // flaps back: still the same open episode
        assertFalse(tracker.apply(Set.of()).changed());
        assertFalse(tracker.apply(Set.of()).changed());
        assertEquals(Set.of(HealthIssue.ERRATIC), tracker.apply(Set.of()).closed());
    }

    @Test
    void offlineClosesAsSoonAsDataArrives() {
        EpisodeTracker tracker = new EpisodeTracker(3, Set.of(HealthIssue.OFFLINE));
        EpisodeTracker.Transition t = tracker.apply(Set.of());
        assertEquals(Set.of(HealthIssue.OFFLINE), t.closed());
    }

    @Test
    void issuesReloadedAfterRestartAreNotReopened() {
        EpisodeTracker tracker = new EpisodeTracker(3, Set.of(HealthIssue.LOSSY));
        EpisodeTracker.Transition t = tracker.apply(Set.of(HealthIssue.LOSSY));
        assertTrue(t.opened().isEmpty());
        assertEquals(Set.of(HealthIssue.LOSSY), t.active());
    }
}

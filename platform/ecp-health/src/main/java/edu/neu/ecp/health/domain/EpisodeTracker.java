package edu.neu.ecp.health.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Adds hysteresis on top of raw detection so issues do not flap open and closed.
 *
 * <ul>
 *   <li>An issue opens the first time it is detected.</li>
 *   <li>It closes after {@link HealthPolicy#recoveryEvaluations()} evaluations in a row without it
 *       (or immediately for issues where one good evaluation is proof, such as OFFLINE).</li>
 * </ul>
 *
 * One instance per sensor. State lives in memory; after a restart, open issues are reloaded from
 * {@code sensor_health_events} and the clean streaks start from zero, which errs on the safe side.
 */
public final class EpisodeTracker {

    private final int recoveryEvaluations;
    private final Set<HealthIssue> open = EnumSet.noneOf(HealthIssue.class);
    private final Map<HealthIssue, Integer> cleanStreak = new EnumMap<>(HealthIssue.class);

    public EpisodeTracker(int recoveryEvaluations, Set<HealthIssue> alreadyOpen) {
        this.recoveryEvaluations = recoveryEvaluations;
        open.addAll(alreadyOpen);
    }

    public Transition apply(Set<HealthIssue> detected) {
        Set<HealthIssue> opened = EnumSet.noneOf(HealthIssue.class);
        Set<HealthIssue> closed = EnumSet.noneOf(HealthIssue.class);

        for (HealthIssue issue : detected) {
            cleanStreak.remove(issue);
            if (open.add(issue)) {
                opened.add(issue);
            }
        }
        for (HealthIssue issue : EnumSet.copyOf(open)) {
            if (detected.contains(issue)) {
                continue;
            }
            int streak = cleanStreak.merge(issue, 1, Integer::sum);
            if (issue.clearsImmediately() || streak >= recoveryEvaluations) {
                open.remove(issue);
                cleanStreak.remove(issue);
                closed.add(issue);
            }
        }
        return new Transition(opened, closed, EnumSet.copyOf(open));
    }

    public Set<HealthIssue> openIssues() {
        return Set.copyOf(open);
    }

    /** What changed in one evaluation. {@code active} is the set of open issues after applying the change. */
    public record Transition(Set<HealthIssue> opened, Set<HealthIssue> closed, Set<HealthIssue> active) {
        public Transition {
            opened = Set.copyOf(opened);
            closed = Set.copyOf(closed);
            active = Set.copyOf(active);
        }

        public boolean changed() {
            return !opened.isEmpty() || !closed.isEmpty();
        }
    }
}

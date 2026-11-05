package edu.neu.ecp.health.domain;

import java.util.Set;

/**
 * Final verdict for one sensor after hysteresis: what the dashboard shows and what analytics relies on.
 *
 * @param trusted false when analytics must ignore this sensor's readings (offline, stuck, impossible or erratic data)
 */
public record HealthAssessment(long sensorId, HealthStatus status, Connectivity connectivity, Set<HealthIssue> activeIssues,
                               int score, boolean trusted, Evaluation evaluation) {

    public HealthAssessment {
        activeIssues = Set.copyOf(activeIssues);
    }

    public static HealthAssessment from(Evaluation evaluation, Set<HealthIssue> activeIssues) {
        HealthStatus status = statusOf(evaluation.connectivity(), activeIssues);
        int score = scoreOf(evaluation.connectivity(), activeIssues);
        boolean trusted = evaluation.connectivity() != Connectivity.NEVER_SEEN
                && activeIssues.stream().allMatch(HealthIssue::dataStillTrusted);
        return new HealthAssessment(evaluation.sensorId(), status, evaluation.connectivity(), activeIssues, score,
                trusted, evaluation);
    }

    static HealthStatus statusOf(Connectivity connectivity, Set<HealthIssue> issues) {
        if (issues.contains(HealthIssue.OFFLINE)) {
            return HealthStatus.OFFLINE;
        }
        if (connectivity == Connectivity.NEVER_SEEN) {
            return HealthStatus.UNKNOWN;
        }
        if (issues.contains(HealthIssue.STUCK)) {
            return HealthStatus.STUCK;
        }
        if (issues.contains(HealthIssue.OUT_OF_RANGE) || issues.contains(HealthIssue.ERRATIC)) {
            return HealthStatus.FAULTY;
        }
        if (!issues.isEmpty() || connectivity == Connectivity.LATE) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.HEALTHY;
    }

    static int scoreOf(Connectivity connectivity, Set<HealthIssue> issues) {
        if (connectivity == Connectivity.NEVER_SEEN && issues.isEmpty()) {
            return 0;
        }
        int score = 100;
        for (HealthIssue issue : issues) {
            score -= issue.scorePenalty();
        }
        if (connectivity == Connectivity.LATE) {
            score -= 10;
        }
        return Math.max(0, Math.min(100, score));
    }
}

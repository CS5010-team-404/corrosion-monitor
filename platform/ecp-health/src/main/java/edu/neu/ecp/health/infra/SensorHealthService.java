package edu.neu.ecp.health.infra;

import edu.neu.ecp.common.audit.AuditEvent;
import edu.neu.ecp.common.audit.AuditRecorder;
import edu.neu.ecp.common.domain.Topics;
import edu.neu.ecp.common.live.LiveUpdates;
import edu.neu.ecp.common.signal.AlertSignal;
import edu.neu.ecp.common.signal.SignalPublisher;
import edu.neu.ecp.common.signal.SignalSource;
import edu.neu.ecp.common.signal.SignalState;
import edu.neu.ecp.health.domain.EpisodeTracker;
import edu.neu.ecp.health.domain.Evaluation;
import edu.neu.ecp.health.domain.HealthAssessment;
import edu.neu.ecp.health.domain.HealthEvaluator;
import edu.neu.ecp.health.domain.HealthIssue;
import edu.neu.ecp.health.domain.Observation;
import edu.neu.ecp.health.domain.ReadingSample;
import edu.neu.ecp.health.domain.SensorRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One evaluation pass over every active sensor: load data, run the pure rules, apply hysteresis, then persist,
 * signal the alert service, audit, and push changes to the dashboard.
 */
@Service
public class SensorHealthService {

    static final String SERVICE = "sensor-health";

    private final HealthEvaluator evaluator;
    private final HealthRepository repository;
    private final SignalPublisher signals;
    private final AuditRecorder audit;
    private final LiveUpdates live;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final HealthQueries queries;
    private final Map<Long, EpisodeTracker> trackers = new ConcurrentHashMap<>();

    public SensorHealthService(HealthEvaluator evaluator, HealthRepository repository, SignalPublisher signals,
                               AuditRecorder audit, LiveUpdates live, Clock clock, TransactionTemplate tx,
                               HealthQueries queries) {
        this.evaluator = evaluator;
        this.repository = repository;
        this.signals = signals;
        this.audit = audit;
        this.live = live;
        this.clock = clock;
        this.tx = tx;
        this.queries = queries;
    }

    public List<HealthAssessment> evaluateAll() {
        Instant now = clock.instant();
        Instant since = now.minus(evaluator.policy().window());
        List<SensorRef> sensors = repository.activeSensors();
        Map<Long, List<ReadingSample>> windows = repository.windows(since);
        Map<String, Integer> failures = repository.signatureFailures(since);
        Map<Long, HealthRepository.StoredHealth> stored = repository.currentHealth();
        Map<Long, Map<HealthIssue, Long>> open = repository.openEpisodes();

        List<HealthAssessment> results = new ArrayList<>(sensors.size());
        for (SensorRef sensor : sensors) {
            List<ReadingSample> window = windows.getOrDefault(sensor.sensorId(), List.of());
            HealthRepository.StoredHealth previous = stored.get(sensor.sensorId());
            Instant lastSeen = lastSeen(sensor, window, previous);
            int sigFailures = failures.getOrDefault(sensor.deviceId(), 0);

            Evaluation evaluation = evaluator.evaluate(new Observation(sensor, window, lastSeen, sigFailures, now));
            Map<HealthIssue, Long> openForSensor = open.getOrDefault(sensor.sensorId(), Map.of());
            EpisodeTracker tracker = trackers.computeIfAbsent(sensor.sensorId(),
                    id -> new EpisodeTracker(evaluator.policy().recoveryEvaluations(), openForSensor.keySet()));
            EpisodeTracker.Transition transition = tracker.apply(evaluation.detected());
            HealthAssessment assessment = HealthAssessment.from(evaluation, transition.active());

            boolean statusChanged = previous == null || !previous.status().equals(assessment.status().name());
            Instant statusSince = statusChanged ? now : previous.statusSince();
            tx.executeWithoutResult(status -> persist(sensor, assessment, transition, openForSensor, statusSince,
                    sigFailures, now));
            if (statusChanged || transition.changed()) {
                queries.sensor(sensor.sensorId()).ifPresent(view -> live.publish(Topics.HEALTH, view));
            }
            results.add(assessment);
        }
        return results;
    }

    private Instant lastSeen(SensorRef sensor, List<ReadingSample> window, HealthRepository.StoredHealth previous) {
        Instant fromWindow = window.stream().map(ReadingSample::receivedAt).max(Instant::compareTo).orElse(null);
        Instant fromStore = previous == null ? null : previous.lastSeenAt();
        if (fromWindow == null && fromStore == null) {
            return repository.lastSeenFromReadings(sensor.sensorId());
        }
        if (fromWindow == null) {
            return fromStore;
        }
        return fromStore == null || fromWindow.isAfter(fromStore) ? fromWindow : fromStore;
    }

    private void persist(SensorRef sensor, HealthAssessment a, EpisodeTracker.Transition t,
                         Map<HealthIssue, Long> openIds, Instant statusSince, int sigFailures, Instant now) {
        for (HealthIssue issue : t.opened()) {
            Map<String, Object> evidence = a.evaluation().evidence().getOrDefault(issue, Map.of());
            repository.openEpisode(sensor.sensorId(), issue, now, evidence);
            signals.publish(signal(sensor, issue, SignalState.RAISED, evidence));
            audit.record(AuditEvent.system(SERVICE, "SENSOR_ISSUE_OPENED", "sensor", sensor.sensorId(),
                    details(sensor, issue, evidence)));
        }
        for (HealthIssue issue : t.closed()) {
            Long eventId = openIds.get(issue);
            if (eventId != null) {
                repository.closeEpisode(eventId, now);
            }
            signals.publish(signal(sensor, issue, SignalState.CLEARED, Map.of()));
            audit.record(AuditEvent.system(SERVICE, "SENSOR_ISSUE_CLOSED", "sensor", sensor.sensorId(),
                    details(sensor, issue, Map.of())));
        }
        repository.upsertHealth(a, statusSince, sigFailures);
    }

    static AlertSignal signal(SensorRef sensor, HealthIssue issue, SignalState state, Map<String, Object> evidence) {
        String title = issue.title() + ": " + sensor.deviceId();
        String message = state == SignalState.CLEARED
                ? sensor.deviceId() + " recovered."
                : sensor.deviceId() + ": " + issue.message(detailFor(issue, evidence));
        Map<String, Object> payload = new LinkedHashMap<>(evidence);
        payload.put("deviceId", sensor.deviceId());
        payload.put("issue", issue.name());
        return new AlertSignal(SignalSource.SENSOR_HEALTH, issue.signalType(), issue.severity(), state,
                sensor.equipmentId(), sensor.sensorId(), dedupKey(issue, sensor.sensorId()), title, message, payload);
    }

    static String dedupKey(HealthIssue issue, long sensorId) {
        return "HEALTH:" + issue.signalType() + ":" + sensorId;
    }

    static Object detailFor(HealthIssue issue, Map<String, Object> e) {
        return switch (issue) {
            case OFFLINE -> humanDuration(((Number) e.getOrDefault("silentSeconds", 0)).longValue());
            case STUCK -> e.getOrDefault("identicalReadings", "?");
            case OUT_OF_RANGE -> e.getOrDefault("count", "?");
            case ERRATIC -> e.getOrDefault("spikes", "?");
            case LOSSY -> e.getOrDefault("missingPct", "?");
            case SIGNATURE_FAILURES -> e.getOrDefault("rejected", "?");
            case CLOCK_SKEW -> e.getOrDefault("medianSkewSeconds", "?");
        };
    }

    static String humanDuration(long seconds) {
        Duration d = Duration.ofSeconds(seconds);
        if (d.toHours() > 0) {
            return d.toHours() + " h " + d.toMinutesPart() + " min";
        }
        if (d.toMinutes() > 0) {
            return d.toMinutes() + " min " + d.toSecondsPart() + " s";
        }
        return seconds + " s";
    }

    private static Map<String, Object> details(SensorRef sensor, HealthIssue issue, Map<String, Object> evidence) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("deviceId", sensor.deviceId());
        d.put("issue", issue.name());
        d.put("severity", issue.severity().name());
        d.putAll(evidence);
        return d;
    }
}

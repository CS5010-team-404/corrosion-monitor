package edu.neu.ecp.health.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The detection rules. A pure function of (observation, policy): no database, no clock, no Spring.
 * That makes every rule easy to test with hand-built readings.
 */
public final class HealthEvaluator {

    private final HealthPolicy policy;

    public HealthEvaluator(HealthPolicy policy) {
        this.policy = policy;
    }

    public HealthPolicy policy() {
        return policy;
    }

    public Evaluation evaluate(Observation o) {
        Set<HealthIssue> detected = EnumSet.noneOf(HealthIssue.class);
        Map<HealthIssue, Map<String, Object>> evidence = new EnumMap<>(HealthIssue.class);
        List<ReadingSample> window = o.window();

        Connectivity connectivity = connectivity(o);
        if (connectivity == Connectivity.OFFLINE) {
            detected.add(HealthIssue.OFFLINE);
            Duration silentFor = o.lastSeenAt() == null
                    ? Duration.between(o.sensor().createdAt(), o.now())
                    : Duration.between(o.lastSeenAt(), o.now());
            evidence.put(HealthIssue.OFFLINE, ordered(
                    "silentSeconds", silentFor.toSeconds(),
                    "neverReported", o.lastSeenAt() == null,
                    "offlineAfterSeconds", threshold(o, policy.offlineFactor()).toSeconds()));
        }

        int outOfRange = (int) window.stream().filter(this::isOutOfRange).count();
        if (outOfRange >= policy.outOfRangeMinCount()) {
            detected.add(HealthIssue.OUT_OF_RANGE);
            ReadingSample example = window.stream().filter(this::isOutOfRange).reduce((a, b) -> b).orElseThrow();
            evidence.put(HealthIssue.OUT_OF_RANGE, ordered(
                    "count", outOfRange,
                    "latestTemperatureC", example.temperatureC(),
                    "latestHumidityPct", example.humidityPct()));
        }

        int stuckRun = trailingIdenticalRun(window);
        if (stuckRun >= policy.stuckMinReadings()) {
            detected.add(HealthIssue.STUCK);
            ReadingSample last = window.get(window.size() - 1);
            evidence.put(HealthIssue.STUCK, ordered(
                    "identicalReadings", stuckRun,
                    "temperatureC", last.temperatureC(),
                    "humidityPct", last.humidityPct()));
        }

        int spikes = countSpikes(window, o.sensor().expectedInterval());
        if (spikes >= policy.erraticMinSpikes()) {
            detected.add(HealthIssue.ERRATIC);
            evidence.put(HealthIssue.ERRATIC, ordered(
                    "spikes", spikes,
                    "spikeTempC", policy.spikeTempC(),
                    "spikeHumidityPct", policy.spikeHumidityPct()));
        }

        LossStats loss = loss(window);
        if (loss.missingPct() != null && loss.missingPct() >= policy.lossyMissingPct()) {
            detected.add(HealthIssue.LOSSY);
            evidence.put(HealthIssue.LOSSY, ordered(
                    "missingPct", round1(loss.missingPct()),
                    "expected", loss.expected(),
                    "received", loss.received()));
        }

        if (o.signatureFailures() >= policy.signatureFailureMin()) {
            detected.add(HealthIssue.SIGNATURE_FAILURES);
            evidence.put(HealthIssue.SIGNATURE_FAILURES, ordered(
                    "rejected", o.signatureFailures(),
                    "windowMinutes", policy.window().toMinutes()));
        }

        Long skew = medianSkewSeconds(window);
        if (skew != null && Math.abs(skew) > policy.clockSkewThreshold().toSeconds()) {
            detected.add(HealthIssue.CLOCK_SKEW);
            evidence.put(HealthIssue.CLOCK_SKEW, ordered("medianSkewSeconds", skew));
        }

        ReadingSample newest = window.isEmpty() ? null : window.get(window.size() - 1);
        Evaluation.WindowMetrics metrics = new Evaluation.WindowMetrics(
                window.size(), loss.expected(), loss.missingPct() == null ? null : round1(loss.missingPct()),
                spikes, outOfRange, skew, o.lastSeenAt(),
                newest == null ? null : newest.seqNo(),
                newest == null ? null : newest.measuredAt());

        return new Evaluation(o.sensor().sensorId(), connectivity, detected, evidence, metrics, o.now());
    }

    // ---------------------------------------------------------------- rules

    Connectivity connectivity(Observation o) {
        if (o.lastSeenAt() == null) {
            boolean overdue = Duration.between(o.sensor().createdAt(), o.now())
                    .compareTo(threshold(o, policy.offlineFactor())) > 0;
            return overdue ? Connectivity.OFFLINE : Connectivity.NEVER_SEEN;
        }
        Duration age = Duration.between(o.lastSeenAt(), o.now());
        if (age.compareTo(threshold(o, policy.offlineFactor())) > 0) {
            return Connectivity.OFFLINE;
        }
        if (age.compareTo(threshold(o, policy.lateFactor())) > 0) {
            return Connectivity.LATE;
        }
        return Connectivity.ONLINE;
    }

    private Duration threshold(Observation o, double factor) {
        return Duration.ofMillis(Math.round(o.sensor().expectedInterval().toMillis() * factor));
    }

    boolean isOutOfRange(ReadingSample r) {
        return Double.isNaN(r.temperatureC()) || Double.isNaN(r.humidityPct())
                || r.temperatureC() < policy.tempMinC() || r.temperatureC() > policy.tempMaxC()
                || r.humidityPct() < policy.humidityMinPct() || r.humidityPct() > policy.humidityMaxPct();
    }

    /** Length of the run of identical readings at the end of the window. */
    int trailingIdenticalRun(List<ReadingSample> window) {
        if (window.isEmpty()) {
            return 0;
        }
        ReadingSample last = window.get(window.size() - 1);
        int run = 1;
        for (int i = window.size() - 2; i >= 0; i--) {
            ReadingSample r = window.get(i);
            boolean same = Math.abs(r.temperatureC() - last.temperatureC()) <= policy.stuckEpsilon()
                    && Math.abs(r.humidityPct() - last.humidityPct()) <= policy.stuckEpsilon();
            if (!same) {
                break;
            }
            run++;
        }
        return run;
    }

    /**
     * Jumps between consecutive readings that arrive close together. Out-of-range readings are excluded,
     * because OUT_OF_RANGE already reports them; a long gap is excluded, because air can change a lot in an hour.
     */
    int countSpikes(List<ReadingSample> window, Duration expectedInterval) {
        Duration maxGap = expectedInterval.multipliedBy(3);
        int spikes = 0;
        ReadingSample previous = null;
        for (ReadingSample r : window) {
            if (isOutOfRange(r)) {
                previous = null;
                continue;
            }
            if (previous != null && Duration.between(previous.receivedAt(), r.receivedAt()).compareTo(maxGap) <= 0) {
                boolean jump = Math.abs(r.temperatureC() - previous.temperatureC()) > policy.spikeTempC()
                        || Math.abs(r.humidityPct() - previous.humidityPct()) > policy.spikeHumidityPct();
                if (jump) {
                    spikes++;
                }
            }
            previous = r;
        }
        return spikes;
    }

    record LossStats(Integer expected, Integer received, Double missingPct) {
    }

    /**
     * Missing readings from sequence-number gaps. If the sequence goes backwards the device restarted, so only
     * the segment after the newest restart is judged.
     */
    LossStats loss(List<ReadingSample> window) {
        if (window.size() < 2) {
            return new LossStats(null, window.size(), null);
        }
        List<ReadingSample> segment = new ArrayList<>();
        for (ReadingSample r : window) {
            if (!segment.isEmpty() && r.seqNo() < segment.get(segment.size() - 1).seqNo()) {
                segment.clear();
            }
            segment.add(r);
        }
        long first = segment.get(0).seqNo();
        long last = segment.get(segment.size() - 1).seqNo();
        int expected = (int) (last - first + 1);
        int received = (int) segment.stream().mapToLong(ReadingSample::seqNo).distinct().count();
        if (expected < policy.lossyMinExpected()) {
            return new LossStats(expected, received, null);
        }
        double missingPct = 100.0 * Math.max(0, expected - received) / expected;
        return new LossStats(expected, received, missingPct);
    }

    /** Median of (received - measured) in seconds; positive means the device clock is behind. */
    Long medianSkewSeconds(List<ReadingSample> window) {
        if (window.size() < 3) {
            return null;
        }
        long[] skews = window.stream()
                .mapToLong(r -> Duration.between(r.measuredAt(), r.receivedAt()).toSeconds())
                .sorted().toArray();
        return skews[skews.length / 2];
    }

    // ---------------------------------------------------------------- helpers

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}

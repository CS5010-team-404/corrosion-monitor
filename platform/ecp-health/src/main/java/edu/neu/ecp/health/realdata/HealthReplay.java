package edu.neu.ecp.health.realdata;

import edu.neu.ecp.health.domain.EpisodeTracker;
import edu.neu.ecp.health.domain.Evaluation;
import edu.neu.ecp.health.domain.HealthEvaluator;
import edu.neu.ecp.health.domain.HealthIssue;
import edu.neu.ecp.health.domain.HealthPolicy;
import edu.neu.ecp.health.domain.Observation;
import edu.neu.ecp.health.domain.ReadingSample;
import edu.neu.ecp.health.domain.SensorRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays one mote's readings through the production health rules ({@link HealthEvaluator} + {@link EpisodeTracker})
 * exactly as the platform job would: an evaluation every {@code every} (5 s in production) over the readings received
 * in the last window. Readings arrive when they were measured (no clock skew information exists in this dataset), the
 * epoch number is the sequence number, and no signatures exist, so CLOCK_SKEW and SIGNATURE_FAILURES cannot fire.
 */
public final class HealthReplay {

    /** Reference categories scored against, in table order. */
    public enum Reference { IMPLAUSIBLE, LOW_BATTERY, FLAT, FAULTY, SILENT }

    /** A rule's open interval. */
    public record Episode(int mote, HealthIssue issue, Instant start, Instant end, Map<String, Object> evidence) {
        public Duration length() {
            return Duration.between(start, end);
        }
    }

    /** Tick-level confusion counts of one rule (or "untrusted") against one reference. */
    public static final class Confusion {
        public long tp;
        public long fp;
        public long fn;
        public long tn;

        void add(boolean predicted, boolean actual) {
            if (predicted && actual) {
                tp++;
            } else if (predicted) {
                fp++;
            } else if (actual) {
                fn++;
            } else {
                tn++;
            }
        }

        public void add(Confusion o) {
            tp += o.tp;
            fp += o.fp;
            fn += o.fn;
            tn += o.tn;
        }

        public double precision() {
            return tp + fp == 0 ? Double.NaN : (double) tp / (tp + fp);
        }

        public double recall() {
            return tp + fn == 0 ? Double.NaN : (double) tp / (tp + fn);
        }
    }

    /** Row names besides the rules: any open issue that makes data untrusted, and the robust z-score detector. */
    public static final String UNTRUSTED = "ANY_UNTRUSTED";
    public static final String ROBUST_Z = "ROBUST_Z (comparison)";
    /** The rules about the values themselves (silence and loss are not bad data). */
    public static final String VALUE_RULES = "VALUE_RULES (STUCK or OUT_OF_RANGE or ERRATIC)";

    /** Key for the confusion table: a detector (rule name, UNTRUSTED or ROBUST_Z) against one reference. */
    public record Cell(String detector, Reference reference) {
    }

    public record Result(int mote, long ticks, List<Episode> episodes, Map<Cell, Confusion> confusion,
                         Map<Reference, Long> referenceTicks) {
    }

    private HealthReplay() {
    }

    public static Result run(int mote, List<IntelLabLoader.Reading> readings, HealthPolicy policy, Duration expected,
                             Duration every, Instant end) {
        ReferenceLabels.Labels labels = ReferenceLabels.of(readings);
        boolean[] robust = RobustZ.flags(readings);
        List<ReadingSample> samples = new ArrayList<>(readings.size());
        for (IntelLabLoader.Reading r : readings) {
            samples.add(new ReadingSample(r.epoch(), r.at(), r.at(), r.temperatureC(), r.humidityPct()));
        }
        Instant start = readings.get(0).at();
        SensorRef ref = new SensorRef(mote, "mote-" + mote, null, expected, start);
        HealthEvaluator evaluator = new HealthEvaluator(policy);
        EpisodeTracker tracker = new EpisodeTracker(policy.recoveryEvaluations(), Set.of());
        Map<HealthIssue, Instant> openSince = new EnumMap<>(HealthIssue.class);
        Map<HealthIssue, Map<String, Object>> openEvidence = new EnumMap<>(HealthIssue.class);
        List<Episode> episodes = new ArrayList<>();
        Confusion[][] cells = new Confusion[RULES.size() + 3][Reference.values().length];
        for (Confusion[] row : cells) {
            for (int r = 0; r < row.length; r++) {
                row[r] = new Confusion();
            }
        }
        long[] refCounts = new long[Reference.values().length];
        long silentAfterMs = Math.round(expected.toMillis() * policy.offlineFactor());
        int lo = 0;
        int hi = 0; // window = readings [lo, hi)
        long ticks = 0;
        for (Instant now = start; !now.isAfter(end); now = now.plus(every)) {
            while (hi < samples.size() && !samples.get(hi).receivedAt().isAfter(now)) {
                hi++;
            }
            Instant from = now.minus(policy.window());
            while (lo < hi && !samples.get(lo).receivedAt().isAfter(from)) {
                lo++;
            }
            List<ReadingSample> window = samples.subList(lo, hi);
            Instant lastSeen = hi == 0 ? null : samples.get(hi - 1).receivedAt();
            Evaluation ev = evaluator.evaluate(new Observation(ref, window, lastSeen, 0, now));
            EpisodeTracker.Transition t = tracker.apply(ev.detected(), ev.undecided());
            for (HealthIssue i : t.opened()) {
                openSince.put(i, now);
                openEvidence.put(i, ev.evidence().get(i));
            }
            for (HealthIssue i : t.closed()) {
                episodes.add(new Episode(mote, i, openSince.remove(i), now, openEvidence.remove(i)));
            }
            ticks++;
            // reference at this tick
            boolean implausible = false;
            for (int k = lo; k < hi; k++) {
                implausible |= labels.implausible()[k];
            }
            boolean low = hi > lo && labels.lowBattery()[hi - 1];
            boolean flat = hi > lo && labels.flat()[hi - 1];
            boolean silent = lastSeen == null || Duration.between(lastSeen, now).toMillis() > silentAfterMs;
            boolean[] refs = {implausible, low, flat, implausible || low || flat, silent};
            Set<HealthIssue> active = t.active();
            boolean untrusted = false;
            for (HealthIssue i : active) {
                untrusted |= !i.dataStillTrusted();
            }
            boolean valueIssue = active.contains(HealthIssue.STUCK) || active.contains(HealthIssue.OUT_OF_RANGE)
                    || active.contains(HealthIssue.ERRATIC);
            for (int r = 0; r < refs.length; r++) {
                if (refs[r]) {
                    refCounts[r]++;
                }
                for (int k = 0; k < RULES.size(); k++) {
                    cells[k][r].add(active.contains(RULES.get(k)), refs[r]);
                }
                cells[RULES.size()][r].add(untrusted, refs[r]);
                cells[RULES.size() + 1][r].add(hi > lo && robust[hi - 1], refs[r]);
                cells[RULES.size() + 2][r].add(valueIssue, refs[r]);
            }
        }
        Map<Cell, Confusion> confusion = new java.util.HashMap<>();
        Map<Reference, Long> refTicks = new EnumMap<>(Reference.class);
        for (int r = 0; r < Reference.values().length; r++) {
            refTicks.put(Reference.values()[r], refCounts[r]);
            for (int k = 0; k < RULES.size(); k++) {
                confusion.put(new Cell(RULES.get(k).name(), Reference.values()[r]), cells[k][r]);
            }
            confusion.put(new Cell(UNTRUSTED, Reference.values()[r]), cells[RULES.size()][r]);
            confusion.put(new Cell(ROBUST_Z, Reference.values()[r]), cells[RULES.size() + 1][r]);
            confusion.put(new Cell(VALUE_RULES, Reference.values()[r]), cells[RULES.size() + 2][r]);
        }
        for (Map.Entry<HealthIssue, Instant> e : openSince.entrySet()) {
            episodes.add(new Episode(mote, e.getKey(), e.getValue(), end, openEvidence.get(e.getKey())));
        }
        return new Result(mote, ticks, episodes, confusion, refTicks);
    }

    /** Rules that can fire on this dataset, in table order. */
    public static final List<HealthIssue> RULES = List.of(HealthIssue.OFFLINE, HealthIssue.STUCK,
            HealthIssue.OUT_OF_RANGE, HealthIssue.ERRATIC, HealthIssue.LOSSY);
}

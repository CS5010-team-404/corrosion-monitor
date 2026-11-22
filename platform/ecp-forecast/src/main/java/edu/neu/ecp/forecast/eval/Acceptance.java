package edu.neu.ecp.forecast.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The acceptance rule from docs/ML_FORECAST.md, as code. A candidate replaces the bundled model only if every check
 * passes. "No worse" allows the reporting precision: 0.005 °C of MAE, 0.005 of F1, 0.05 false alarms per day.
 */
public final class Acceptance {

    public static final double MAE_TOLERANCE_C = 0.005;
    public static final double F1_TOLERANCE = 0.005;
    public static final double FALSE_ALARM_TOLERANCE = 0.05;
    public static final double MAX_INFERENCE_MS = 5.0;
    public static final long MAX_MODEL_BYTES = 1_000_000;

    private Acceptance() {
    }

    /** The numbers the rule looks at, for the candidate and the production model. */
    public record Evidence(Metrics.Result lolo, Metrics.Result chrono, Metrics.Result sim) {
    }

    public record Check(String id, String description, boolean passed, String detail) {
    }

    public record Decision(boolean accepted, List<Check> checks) {
        public List<Check> failed() {
            return checks.stream().filter(c -> !c.passed()).toList();
        }
    }

    public static Decision decide(Evidence candidate, Evidence production, double inferenceMedianMs, long modelBytes) {
        List<Check> checks = new ArrayList<>();
        checks.add(lower("a1", "LOLO pooled MAE lower at every horizon", candidate.lolo().mae(), production.lolo().mae()));
        checks.add(lower("a2", "Chronological MAE lower at every horizon", candidate.chrono().mae(), production.chrono().mae()));
        checks.add(noWorse("b", "Simulated test MAE no worse at every horizon", candidate.sim().mae(), production.sim().mae()));
        checks.add(f1("c1", "LOLO warning F1 (30, 60 min) no worse", candidate.lolo(), production.lolo()));
        checks.add(f1("c2", "Simulated warning F1 (30, 60 min) no worse", candidate.sim(), production.sim()));
        checks.add(falseAlarms("c3", "LOLO false alarms per day no higher", candidate.lolo(), production.lolo()));
        checks.add(falseAlarms("c4", "Simulated false alarms per day no higher", candidate.sim(), production.sim()));
        checks.add(new Check("d1", "Median inference under 5 ms per forecast", inferenceMedianMs < MAX_INFERENCE_MS,
                String.format(Locale.ROOT, "%.3f ms", inferenceMedianMs)));
        checks.add(new Check("d2", "Model file under 1 MB", modelBytes < MAX_MODEL_BYTES, modelBytes + " bytes"));
        return new Decision(checks.stream().allMatch(Check::passed), checks);
    }

    static Check lower(String id, String d, double[] cand, double[] prod) {
        boolean ok = true;
        for (int i = 0; i < cand.length; i++) {
            ok &= cand[i] < prod[i];
        }
        return new Check(id, d, ok, pairs(cand, prod, "%.3f"));
    }

    static Check noWorse(String id, String d, double[] cand, double[] prod) {
        boolean ok = true;
        for (int i = 0; i < cand.length; i++) {
            ok &= cand[i] <= prod[i] + MAE_TOLERANCE_C;
        }
        return new Check(id, d, ok, pairs(cand, prod, "%.3f"));
    }

    static Check f1(String id, String d, Metrics.Result cand, Metrics.Result prod) {
        boolean ok = true;
        for (int i = 0; i < 2; i++) {
            ok &= cand.f1()[i] >= prod.f1()[i] - F1_TOLERANCE;
        }
        return new Check(id, d, ok, pairs(cand.f1(), prod.f1(), "%.3f"));
    }

    static Check falseAlarms(String id, String d, Metrics.Result cand, Metrics.Result prod) {
        boolean ok = cand.falseAlarmsPerDay() <= prod.falseAlarmsPerDay() + FALSE_ALARM_TOLERANCE;
        return new Check(id, d, ok, String.format(Locale.ROOT, "%.2f vs %.2f", cand.falseAlarmsPerDay(), prod.falseAlarmsPerDay()));
    }

    private static String pairs(double[] cand, double[] prod, String fmt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cand.length; i++) {
            sb.append(i == 0 ? "" : "; ").append(String.format(Locale.ROOT, fmt + " vs " + fmt, cand[i], prod[i]));
        }
        return sb.toString();
    }
}

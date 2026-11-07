package edu.neu.ecp.forecast.train;

import edu.neu.ecp.forecast.model.Features;
import edu.neu.ecp.forecast.model.Logistic;
import edu.neu.ecp.forecast.model.MarginForecaster;
import edu.neu.ecp.forecast.model.MarginPoint;
import edu.neu.ecp.forecast.model.ModelIO;
import edu.neu.ecp.forecast.model.Ridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trains, validates and evaluates the condensation-margin forecaster, then writes the model file and a report.
 *
 * <pre>
 *   mvn -q -pl ecp-forecast -am exec:java -Dexec.args="--out ecp-forecast/src/main/resources/models"
 *   ... --uci energydata_complete.csv      also evaluates on real UCI room data (rooms 1-9)
 * </pre>
 *
 * Evaluation is honest: chronological per-run split, unseen simulator seeds for testing, and two baselines
 * (persistence: "no change", and the linear trend the UI used before the model existed).
 */
public final class Trainer {

    static final double CRITICAL_C = 1.0;
    static final double[] LAMBDAS = {1e-4, 1e-3, 1e-2, 1e-1};

    public record Dataset(List<double[]> x, Map<Integer, double[]> dy, double[] marginNow, double[] trendSlope) {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of("models");
        Path uci = null;
        int trainRuns = 240;
        int testRuns = 60;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--uci" -> uci = Path.of(args[++i]);
                case "--train-runs" -> trainRuns = Integer.parseInt(args[++i]);
                case "--test-runs" -> testRuns = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Files.createDirectories(out);
        Result r = train(trainRuns, testRuns, 8);
        StringBuilder report = new StringBuilder(r.report());
        if (uci != null) {
            report.append(evaluateUci(r.forecaster(), uci));
        }
        Files.writeString(out.resolve("margin-forecast.json"), ModelIO.toJson(r.forecaster()));
        Files.writeString(out.resolve("margin-forecast-report.md"), report);
        System.out.println(report);
    }

    public record Result(MarginForecaster forecaster, String report) {
    }

    public static Result train(int trainRuns, int testRuns, int hours) {
        List<List<MarginPoint>> train = new ArrayList<>();
        for (int s = 0; s < trainRuns; s++) {
            train.add(SimulatedHistory.run(1000 + s, hours));
        }
        List<List<MarginPoint>> test = new ArrayList<>();
        for (int s = 0; s < testRuns; s++) {
            test.add(SimulatedHistory.run(900_000 + s, hours));
        }
        int cut = (int) (train.size() * 0.8);
        Dataset fitPart = build(train.subList(0, cut), 2);
        Dataset valPart = build(train.subList(cut, train.size()), 2);
        Dataset all = build(train, 2);
        Dataset eval = build(test, 1);

        Map<Integer, Ridge.LinearModel> models = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        StringBuilder rep = new StringBuilder("# Margin forecaster: training report\n\n")
                .append("Model: robust (Huber) ridge regression per horizon on ").append(Features.NAMES.size())
                .append(" features, predicting the change in margin.\n")
                .append("Data: ").append(trainRuns).append(" simulated ").append(hours).append("-hour runs for training, ")
                .append(testRuns).append(" unseen runs for testing (").append(eval.x().size()).append(" test points).\n\n")
                .append("| Horizon | λ | MAE model | MAE trend baseline | MAE no-change baseline | Improvement vs no-change |\n")
                .append("|---|---|---|---|---|---|\n");
        for (int h : MarginForecaster.HORIZONS) {
            double bestLambda = LAMBDAS[0];
            double bestMae = Double.MAX_VALUE;
            for (double lambda : LAMBDAS) {
                Ridge.LinearModel m = Ridge.fitHuber(fitPart.x(), fitPart.dy().get(h), lambda, huberDelta(h), 6);
                double mae = mae(m, valPart, h);
                if (mae < bestMae) {
                    bestMae = mae;
                    bestLambda = lambda;
                }
            }
            Ridge.LinearModel m = Ridge.fitHuber(all.x(), all.dy().get(h), bestLambda, huberDelta(h), 6);
            models.put(h, m);
            double maeModel = mae(m, eval, h);
            double maePersist = 0;
            double maeTrend = 0;
            double[] dy = eval.dy().get(h);
            for (int i = 0; i < dy.length; i++) {
                maePersist += Math.abs(dy[i]);
                maeTrend += Math.abs(dy[i] - eval.trendSlope()[i] * h);
            }
            maePersist /= dy.length;
            maeTrend /= dy.length;
            double gain = 100 * (1 - maeModel / maePersist);
            metrics.put("mae_" + h + "m", round(maeModel));
            metrics.put("mae_no_change_" + h + "m", round(maePersist));
            metrics.put("mae_trend_" + h + "m", round(maeTrend));
            rep.append(String.format(Locale.ROOT, "| %d min | %.0e | %.3f °C | %.3f °C | %.3f °C | %.0f%% |%n",
                    h, bestLambda, maeModel, maeTrend, maePersist, gain));
        }
        rep.append("\nOn the minutes where conditions are actually changing (|30-min trend| > 0.02 °C/min, the cases\n")
           .append("an operator cares about), the gap to the baselines is larger:\n\n")
           .append("| Horizon | Points | MAE model | MAE no-change |\n|---|---|---|---|\n");
        for (int h : MarginForecaster.HORIZONS) {
            double mm = 0, mp = 0;
            int k = 0;
            double[] dy = eval.dy().get(h);
            for (int i = 0; i < dy.length; i++) {
                if (Math.abs(eval.trendSlope()[i]) > 0.02) {
                    mm += Math.abs(dy[i] - models.get(h).predict(eval.x().get(i)));
                    mp += Math.abs(dy[i]);
                    k++;
                }
            }
            metrics.put("mae_active_" + h + "m", round(mm / Math.max(1, k)));
            metrics.put("mae_active_no_change_" + h + "m", round(mp / Math.max(1, k)));
            rep.append(String.format(Locale.ROOT, "| %d min | %d | %.3f °C | %.3f °C |%n", h, k, mm / Math.max(1, k), mp / Math.max(1, k)));
        }

        rep.append("\n## Early warning classifier: will the margin reach ").append(CRITICAL_C).append(" °C within the horizon?\n\n")
           .append("Logistic regression, positives weighted 3×, threshold chosen on validation data to maximise F1.\n")
           .append("Counted only where the margin is above ").append(CRITICAL_C).append(" °C now. Many events start with a sudden,\n")
           .append("unpredictable failure, so recall can never reach 100% from sensor data alone.\n\n")
           .append("| Horizon | Threshold | Precision | Recall | F1 | Events | Regression-only recall |\n|---|---|---|---|---|---|---|\n");
        Map<Integer, Logistic.Classifier> classifiers = new LinkedHashMap<>();
        for (int h : MarginForecaster.WARN_HORIZONS) {
            Logistic.Classifier c = Logistic.fit(fitPart.x(), labels(fitPart, h), 1e-4, 3.0, 12);
            double bestT = 0.5, bestF1 = -1;
            boolean[] vl = labels(valPart, h);
            for (double t = 0.1; t <= 0.9; t += 0.05) {
                double[] prf = prf(c, valPart, vl, t);
                if (prf[2] > bestF1) {
                    bestF1 = prf[2];
                    bestT = t;
                }
            }
            c = Logistic.fit(all.x(), labels(all, h), 1e-4, 3.0, 12).withThreshold(bestT);
            classifiers.put(h, c);
            boolean[] el = labels(eval, h);
            double[] prf = prf(c, eval, el, bestT);
            int events = 0, regHit = 0;
            for (int i = 0; i < el.length; i++) {
                if (el[i]) {
                    events++;
                    if (eval.marginNow()[i] + models.get(h).predict(eval.x().get(i)) <= CRITICAL_C) {
                        regHit++;
                    }
                }
            }
            metrics.put("warn_threshold_" + h + "m", round(bestT));
            metrics.put("warn_precision_" + h + "m", round(prf[0]));
            metrics.put("warn_recall_" + h + "m", round(prf[1]));
            metrics.put("warn_f1_" + h + "m", round(prf[2]));
            rep.append(String.format(Locale.ROOT, "| %d min | %.2f | %.2f | %.2f | %.2f | %d | %.2f |%n", h, bestT, prf[0], prf[1], prf[2], events,
                    events == 0 ? 0 : (double) regHit / events));
        }
        MarginForecaster fc = new MarginForecaster("eval", models, classifiers, metrics);
        double[] lead = leadTimes(fc, test);
        metrics.put("lead_median_model_min", round(lead[0]));
        metrics.put("lead_median_rule_min", round(lead[1]));
        metrics.put("warned_5min_ahead_model", round(lead[2]));
        metrics.put("warned_5min_ahead_rule", round(lead[3]));
        metrics.put("false_alarm_episodes_per_day_model", round(lead[4]));
        metrics.put("false_alarm_episodes_per_day_rule", round(lead[5]));
        rep.append("\n## Warning lead time (what operators feel)\n\n")
           .append("For every moment the margin crosses ").append(CRITICAL_C).append(" °C in the test runs (")
           .append((int) lead[6]).append(" crossings), how long before it did a warning start?\n")
           .append("Rule baseline: warn when the margin is at or below the 3 °C warning threshold (what analytics does without ML).\n\n")
           .append("| | Median lead time | Warned at least 5 min ahead | False-alarm episodes per day |\n|---|---|---|---|\n")
           .append(String.format(Locale.ROOT, "| Model (60-min classifier or forecast) | %.0f min | %.0f%% | %.1f |%n", lead[0], 100 * lead[2], lead[4]))
           .append(String.format(Locale.ROOT, "| Rule: margin ≤ 3 °C | %.0f min | %.0f%% | %.1f |%n", lead[1], 100 * lead[3], lead[5]));
        rep.append("\nWeights (standardised features) are in the model file. Simulated data cannot prove real-world\n")
           .append("accuracy; run with `--uci energydata_complete.csv` to check on real room humidity as well.\n");
        String version = "ridge-" + LocalDate.now() + "-s" + trainRuns;
        metrics.put("trained_on", trainRuns + " simulated runs");
        return new Result(new MarginForecaster(version, models, classifiers, metrics), rep.toString());
    }

    /** Rows every {@code stride} minutes where both 30 min of history and 60 min of future exist. */
    public static Dataset build(List<List<MarginPoint>> runs, int stride) {
        List<double[]> x = new ArrayList<>();
        Map<Integer, List<Double>> dy = new LinkedHashMap<>();
        for (int h : MarginForecaster.HORIZONS) {
            dy.put(h, new ArrayList<>());
        }
        List<Double> now = new ArrayList<>();
        List<Double> slope = new ArrayList<>();
        for (List<MarginPoint> s : runs) {
            for (int t = Features.HISTORY_MINUTES - 1; t + 60 < s.size(); t += stride) {
                x.add(Features.at(s, t));
                double m = s.get(t).marginC();
                now.add(m);
                slope.add(MarginForecaster.trendSlope(s.subList(t - 29, t + 1)));
                for (int h : MarginForecaster.HORIZONS) {
                    dy.get(h).add(s.get(t + h).marginC() - m);
                }
            }
        }
        Map<Integer, double[]> dyArr = new LinkedHashMap<>();
        dy.forEach((h, l) -> dyArr.put(h, l.stream().mapToDouble(Double::doubleValue).toArray()));
        return new Dataset(x, dyArr, now.stream().mapToDouble(Double::doubleValue).toArray(),
                slope.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /**
     * Replays each test run minute by minute. Returns {median lead model, median lead rule, share warned ≥5 min
     * model, share rule, false-alarm episodes/day model, rule, crossings}. A warning "episode" is a run of
     * consecutive warning minutes; it is a false alarm if no crossing follows within 60 minutes of its start.
     */
    static double[] leadTimes(MarginForecaster f, List<List<MarginPoint>> runs) {
        List<Double> leadModel = new ArrayList<>();
        List<Double> leadRule = new ArrayList<>();
        int falseModel = 0, falseRule = 0;
        double minutes = 0;
        for (List<MarginPoint> s : runs) {
            int n = s.size();
            boolean[] wm = new boolean[n];
            boolean[] wr = new boolean[n];
            double[] m = new double[n];
            for (int t = 0; t < n; t++) {
                m[t] = s.get(t).marginC();
                wr[t] = m[t] > CRITICAL_C && m[t] <= 3.0;
                if (t >= Features.HISTORY_MINUTES - 1) {
                    var fc = f.forecast(s.subList(t - Features.HISTORY_MINUTES + 1, t + 1));
                    wm[t] = fc.warn60() || (m[t] > CRITICAL_C && fc.margin60C() <= CRITICAL_C);
                }
            }
            minutes += n - Features.HISTORY_MINUTES;
            smooth(wm);
            smooth(wr);
            List<Integer> crossings = new ArrayList<>();
            boolean armed = false; // a new episode needs the margin to have recovered above 3 °C first
            for (int c = 1; c < n; c++) {
                if (m[c] >= 3.0) {
                    armed = true;
                }
                if (armed && m[c] <= CRITICAL_C && m[c - 1] > CRITICAL_C) {
                    armed = false;
                    crossings.add(c);
                    if (c >= 60) {
                        leadModel.add(lead(wm, c));
                        leadRule.add(lead(wr, c));
                    }
                }
            }
            falseModel += falseEpisodes(wm, crossings);
            falseRule += falseEpisodes(wr, crossings);
        }
        double days = minutes / (60.0 * 24);
        return new double[] {median(leadModel), median(leadRule), share(leadModel, 5), share(leadRule, 5),
            falseModel / days, falseRule / days, leadModel.size()};
    }

    /** Debounce: gaps of up to 5 minutes inside a warning are filled, as an alert service would do. */
    static void smooth(boolean[] w) {
        int last = -100;
        for (int t = 0; t < w.length; t++) {
            if (w[t]) {
                if (t - last > 1 && t - last <= 6) {
                    for (int k = last + 1; k < t; k++) {
                        w[k] = true;
                    }
                }
                last = t;
            }
        }
    }

    /** Minutes of continuous warning immediately before the crossing (0 if none), capped at 60. */
    static double lead(boolean[] w, int c) {
        int k = 0;
        while (k < 60 && c - 1 - k >= 0 && w[c - 1 - k]) {
            k++;
        }
        return k;
    }

    static int falseEpisodes(boolean[] w, List<Integer> crossings) {
        int count = 0;
        for (int t = 0; t < w.length; t++) {
            if (w[t] && (t == 0 || !w[t - 1])) {
                final int start = t;
                if (crossings.stream().noneMatch(c -> c >= start && c <= start + 60)) {
                    count++;
                }
            }
        }
        return count;
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compare);
        return s.get(s.size() / 2);
    }

    static double share(List<Double> v, double atLeast) {
        return v.isEmpty() ? 0 : v.stream().filter(x -> x >= atLeast).count() / (double) v.size();
    }

    /** Label: margin above critical now and at or below critical at t + h. Points already critical are excluded (false). */
    static boolean[] labels(Dataset d, int h) {
        boolean[] l = new boolean[d.marginNow().length];
        double[] dy = d.dy().get(h);
        for (int i = 0; i < l.length; i++) {
            l[i] = d.marginNow()[i] > CRITICAL_C && d.marginNow()[i] + dy[i] <= CRITICAL_C;
        }
        return l;
    }

    /** Precision, recall and F1 on points whose margin is above critical now. */
    static double[] prf(Logistic.Classifier c, Dataset d, boolean[] label, double threshold) {
        int tp = 0, fp = 0, fn = 0;
        for (int i = 0; i < label.length; i++) {
            if (d.marginNow()[i] <= CRITICAL_C) {
                continue;
            }
            boolean pred = c.probability(d.x().get(i)) >= threshold;
            if (pred && label[i]) {
                tp++;
            } else if (pred) {
                fp++;
            } else if (label[i]) {
                fn++;
            }
        }
        double p = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double r = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        return new double[] {p, r, p + r == 0 ? 0 : 2 * p * r / (p + r)};
    }

    /** Errors above this many °C count linearly, not squared. */
    static double huberDelta(int h) {
        return 0.1 + 0.01 * h;
    }

    static double mae(Ridge.LinearModel m, Dataset d, int h) {
        double[] y = d.dy().get(h);
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            s += Math.abs(y[i] - m.predict(d.x().get(i)));
        }
        return s / y.length;
    }

    static String evaluateUci(MarginForecaster f, Path csv) throws IOException {
        StringBuilder rep = new StringBuilder("\n## Real data check: UCI Appliances Energy Prediction\n\n")
                .append("Surface assumed 2 °C below air. MAE of the predicted margin change.\n\n")
                .append("| Room | Points | MAE 60 min model | MAE 60 min no-change |\n|---|---|---|---|\n");
        for (int room = 1; room <= 9; room++) {
            Dataset d = build(List.of(UciLoader.room(csv, room, 2.0)), 10);
            double model = mae(f.models().get(60), d, 60);
            double persist = 0;
            for (double v : d.dy().get(60)) {
                persist += Math.abs(v);
            }
            persist /= d.dy().get(60).length;
            rep.append(String.format(Locale.ROOT, "| %d | %d | %.3f °C | %.3f °C |%n", room, d.x().size(), model, persist));
        }
        return rep.toString();
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

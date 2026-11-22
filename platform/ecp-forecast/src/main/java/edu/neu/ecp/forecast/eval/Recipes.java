package edu.neu.ecp.forecast.eval;

import edu.neu.ecp.forecast.model.Gbt;
import edu.neu.ecp.forecast.model.GbtForecaster;
import edu.neu.ecp.forecast.model.Logistic;
import edu.neu.ecp.forecast.model.MarginForecaster;
import edu.neu.ecp.forecast.model.Ridge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How each candidate is trained. Every choice (ridge λ, GBT depth / leaf size / tree count, warning threshold) is made
 * on the validation rows only; the final model is then refitted on fit + validation rows. Test rows never enter here.
 */
public final class Recipes {

    static final double[] LAMBDAS = {1e-4, 1e-3, 1e-2, 1e-1};
    static final double POSITIVE_WEIGHT = 3.0;
    static final int[] DEPTHS = {3, 5};
    static final int[] MIN_LEAVES = {20, 100};
    /** Protocol: up to 500 trees, early stopping after 30 rounds without validation improvement. */
    static final int MAX_TREES = 500;

    private Recipes() {
    }

    /** Training rows split for tuning: {@code fit} trains candidates, {@code validation} chooses between them. */
    public record Split(List<Example> fit, List<Example> validation) {
        public List<Example> all() {
            List<Example> a = new ArrayList<>(fit);
            a.addAll(validation);
            return a;
        }
    }

    /** Same recipe as the production trainer (Huber ridge + 3×-weighted logistic, F1-optimal threshold). */
    public static MarginForecaster ridge(String version, Split s) {
        List<Example> all = s.all();
        Map<Integer, Ridge.LinearModel> models = new LinkedHashMap<>();
        Map<String, Object> notes = new LinkedHashMap<>();
        for (int i = 0; i < MarginForecaster.HORIZONS.length; i++) {
            int h = MarginForecaster.HORIZONS[i];
            double delta = huberDelta(h);
            double bestLambda = LAMBDAS[0];
            double best = Double.MAX_VALUE;
            for (double lambda : LAMBDAS) {
                Ridge.LinearModel m = Ridge.fitHuber(x(s.fit()), dy(s.fit(), i), lambda, delta, 6);
                double mae = mae(m::predict, s.validation(), i);
                if (mae < best) {
                    best = mae;
                    bestLambda = lambda;
                }
            }
            models.put(h, Ridge.fitHuber(x(all), dy(all, i), bestLambda, delta, 6));
            notes.put("lambda_" + h + "m", bestLambda);
        }
        Map<Integer, Logistic.Classifier> classifiers = new LinkedHashMap<>();
        for (int h : MarginForecaster.WARN_HORIZONS) {
            Logistic.Classifier c = Logistic.fit(x(s.fit()), labels(s.fit(), h), 1e-4, POSITIVE_WEIGHT, 12);
            double t = bestThreshold(c::probability, s.validation(), h);
            classifiers.put(h, Logistic.fit(x(all), labels(all, h), 1e-4, POSITIVE_WEIGHT, 12).withThreshold(t));
            notes.put("warn_threshold_" + h + "m", t);
        }
        return new MarginForecaster(version, models, classifiers, notes);
    }

    /** GBT recipe: grid over depth × min leaf, early stopping on validation, then refit with the chosen tree count. */
    public static GbtForecaster gbt(String version, Split s) {
        return gbt(version, s, MAX_TREES);
    }

    /** As {@link #gbt(String, Split)} with a lower tree cap (quick mode and tests only). */
    static GbtForecaster gbt(String version, Split s, int maxTrees) {
        List<Example> all = s.all();
        Map<Integer, Gbt.Model> regs = new LinkedHashMap<>();
        Map<Integer, Gbt.Model> cls = new LinkedHashMap<>();
        Map<String, Object> notes = new LinkedHashMap<>();
        for (int i = 0; i < MarginForecaster.HORIZONS.length; i++) {
            int h = MarginForecaster.HORIZONS[i];
            Choice c = choose(Gbt.Loss.HUBER, huberDelta(h), maxTrees, x(s.fit()), dy(s.fit(), i), null,
                    x(s.validation()), dy(s.validation(), i));
            regs.put(h, Gbt.fit(x(all), dy(all, i), null, c.params().withTrees(Math.max(1, c.trees()))));
            notes.put("gbt_" + h + "m", c.describe());
        }
        for (int h : MarginForecaster.WARN_HORIZONS) {
            double[] yf = binary(labels(s.fit(), h));
            Choice c = choose(Gbt.Loss.LOGISTIC, 1.0, maxTrees, x(s.fit()), yf, weights(yf), x(s.validation()),
                    binary(labels(s.validation(), h)));
            double t = bestThreshold(c.model()::predict, s.validation(), h);
            double[] ya = binary(labels(all, h));
            cls.put(h, Gbt.fit(x(all), ya, weights(ya), c.params().withTrees(Math.max(1, c.trees()))).withThreshold(t));
            notes.put("gbt_warn_" + h + "m", c.describe() + " threshold " + t);
        }
        return new GbtForecaster(version, regs, cls, notes);
    }

    record Choice(Gbt.Params params, int trees, double score, Gbt.Model model) {
        String describe() {
            return "depth " + params.maxDepth() + " minLeaf " + params.minLeaf() + " trees " + trees;
        }
    }

    static Choice choose(Gbt.Loss loss, double delta, int maxTrees, List<double[]> xf, double[] yf, double[] wf,
                         List<double[]> xv, double[] yv) {
        List<Gbt.Params> grid = new ArrayList<>();
        for (int depth : DEPTHS) {
            for (int leaf : MIN_LEAVES) {
                grid.add(Gbt.Params.of(loss, depth, leaf).withHuberDelta(delta).withTrees(maxTrees));
            }
        }
        // fitted in parallel, chosen in grid order (ties go to the earlier, simpler setting): deterministic
        List<Gbt.Fit> fits = grid.parallelStream().map(p -> Gbt.fit(xf, yf, wf, xv, yv, p)).toList();
        Choice best = null;
        for (int i = 0; i < grid.size(); i++) {
            Gbt.Fit fit = fits.get(i);
            double score = fit.validationCurve()[fit.bestTrees()];
            if (best == null || score < best.score() - 1e-12) {
                best = new Choice(grid.get(i), fit.bestTrees(), score, fit.model());
            }
        }
        return best;
    }

    /** Same robustness setting as the production trainer: errors above this many °C count linearly. */
    static double huberDelta(int h) {
        return 0.1 + 0.01 * h;
    }

    /** Threshold in 0.10..0.90 (step 0.05) with the best validation F1 on points above the critical level. */
    static double bestThreshold(java.util.function.ToDoubleFunction<double[]> probability, List<Example> val, int h) {
        double bestT = 0.5;
        double bestF1 = -1;
        for (int k = 0; k <= 16; k++) {
            double t = Math.round((0.10 + 0.05 * k) * 100) / 100.0;
            int tp = 0;
            int fp = 0;
            int fn = 0;
            for (Example e : val) {
                if (e.marginNow() <= Examples.CRITICAL_C) {
                    continue;
                }
                boolean pred = probability.applyAsDouble(e.x()) >= t;
                boolean y = e.within(h);
                if (pred && y) {
                    tp++;
                } else if (pred) {
                    fp++;
                } else if (y) {
                    fn++;
                }
            }
            double f1 = tp == 0 ? 0 : 2.0 * tp / (2.0 * tp + fp + fn);
            if (f1 > bestF1 + 1e-12) {
                bestF1 = f1;
                bestT = t;
            }
        }
        return bestT;
    }

    static List<double[]> x(List<Example> rows) {
        List<double[]> out = new ArrayList<>(rows.size());
        for (Example e : rows) {
            out.add(e.x());
        }
        return out;
    }

    /** Target: change in margin at horizon index i. */
    static double[] dy(List<Example> rows, int i) {
        double[] y = new double[rows.size()];
        for (int k = 0; k < y.length; k++) {
            y[k] = rows.get(k).future()[i] - rows.get(k).marginNow();
        }
        return y;
    }

    static boolean[] labels(List<Example> rows, int h) {
        boolean[] l = new boolean[rows.size()];
        for (int k = 0; k < l.length; k++) {
            l[k] = rows.get(k).within(h);
        }
        return l;
    }

    static double[] binary(boolean[] l) {
        double[] y = new double[l.length];
        for (int i = 0; i < l.length; i++) {
            y[i] = l[i] ? 1 : 0;
        }
        return y;
    }

    static double[] weights(double[] y) {
        double[] w = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            w[i] = y[i] == 1 ? POSITIVE_WEIGHT : 1.0;
        }
        return w;
    }

    static double mae(java.util.function.ToDoubleFunction<double[]> predictDelta, List<Example> rows, int i) {
        double s = 0;
        for (Example e : rows) {
            s += Math.abs(e.future()[i] - e.marginNow() - predictDelta.applyAsDouble(e.x()));
        }
        return s / rows.size();
    }
}

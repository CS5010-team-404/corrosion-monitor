package edu.neu.ecp.forecast.eval;

import edu.neu.ecp.forecast.model.MarginForecaster;
import java.util.ArrayList;
import java.util.List;

/**
 * The protocol's metrics. Scoring is split from aggregation so leave-one-location-out results (each location scored by
 * its own fold's model) can be pooled: score each series with its model, then aggregate any set of scored series.
 */
public final class Metrics {

    static final int LEAD_CAP_MIN = 60;

    private Metrics() {
    }

    /** One series (a location segment or a simulated run) scored by one predictor. Examples are in time order. */
    public record Scored(int location, int stepMinutes, List<Example> examples, List<double[]> margins,
                         boolean[] warn30, boolean[] warn60, boolean[] alarm) {
    }

    public static Scored score(Predictor p, List<Example> series, int stepMinutes) {
        int n = series.size();
        List<double[]> margins = new ArrayList<>(n);
        boolean[] w30 = new boolean[n];
        boolean[] w60 = new boolean[n];
        boolean[] alarm = new boolean[n];
        for (int i = 0; i < n; i++) {
            Example e = series.get(i);
            margins.add(e.scored() ? p.margins(e) : null);
            w30[i] = p.warn(e, 30);
            w60[i] = p.warn(e, 60);
            alarm[i] = p.alarm(e);
        }
        return new Scored(series.isEmpty() ? -1 : series.get(0).location(), stepMinutes, series, margins, w30, w60, alarm);
    }

    /** Everything reported for one predictor on one test set. NaN where it does not apply. */
    public record Result(int points, double[] mae, double[] rmse, int movingPoints, double[] maeMoving,
                         double[] rmseMoving, double[] precision, double[] recall, double[] f1, int[] events,
                         int crossings, double medianLeadMin, double warnedAtLeast10Min,
                         double falseAlarmsPerDay, double days) {
    }

    public static Result aggregate(List<Scored> all) {
        int h = MarginForecaster.HORIZONS.length;
        double[] ae = new double[h];
        double[] se = new double[h];
        double[] aeM = new double[h];
        double[] seM = new double[h];
        int n = 0;
        int nM = 0;
        boolean hasMargins = false;
        int[] tp = new int[2];
        int[] fp = new int[2];
        int[] fn = new int[2];
        List<Double> leads = new ArrayList<>();
        int falseEpisodes = 0;
        double minutes = 0;
        for (Scored s : all) {
            for (int i = 0; i < s.examples().size(); i++) {
                Example e = s.examples().get(i);
                if (!e.scored()) {
                    continue;
                }
                double[] m = s.margins().get(i);
                if (m != null) {
                    hasMargins = true;
                    n++;
                    if (e.moving()) {
                        nM++;
                    }
                    for (int k = 0; k < h; k++) {
                        double err = m[k] - e.future()[k];
                        ae[k] += Math.abs(err);
                        se[k] += err * err;
                        if (e.moving()) {
                            aeM[k] += Math.abs(err);
                            seM[k] += err * err;
                        }
                    }
                }
                if (e.marginNow() > Examples.CRITICAL_C) {
                    for (int k = 0; k < 2; k++) {
                        boolean pred = k == 0 ? s.warn30()[i] : s.warn60()[i];
                        boolean y = e.within(MarginForecaster.WARN_HORIZONS[k]);
                        if (pred && y) {
                            tp[k]++;
                        } else if (pred) {
                            fp[k]++;
                        } else if (y) {
                            fn[k]++;
                        }
                    }
                }
            }
            Replay r = replay(s);
            leads.addAll(r.leads());
            falseEpisodes += r.falseEpisodes();
            minutes += (double) s.examples().size() * s.stepMinutes();
        }
        double[] mae = new double[h];
        double[] rmse = new double[h];
        double[] maeM = new double[h];
        double[] rmseM = new double[h];
        for (int k = 0; k < h; k++) {
            mae[k] = hasMargins ? ae[k] / n : Double.NaN;
            rmse[k] = hasMargins ? Math.sqrt(se[k] / n) : Double.NaN;
            maeM[k] = hasMargins && nM > 0 ? aeM[k] / nM : Double.NaN;
            rmseM[k] = hasMargins && nM > 0 ? Math.sqrt(seM[k] / nM) : Double.NaN;
        }
        double[] prec = new double[2];
        double[] rec = new double[2];
        double[] f1 = new double[2];
        int[] events = new int[2];
        for (int k = 0; k < 2; k++) {
            prec[k] = tp[k] + fp[k] == 0 ? 0 : (double) tp[k] / (tp[k] + fp[k]);
            rec[k] = tp[k] + fn[k] == 0 ? 0 : (double) tp[k] / (tp[k] + fn[k]);
            f1[k] = tp[k] == 0 ? 0 : 2.0 * tp[k] / (2.0 * tp[k] + fp[k] + fn[k]);
            events[k] = tp[k] + fn[k];
        }
        double days = minutes / 1440.0;
        return new Result(n, mae, rmse, nM, maeM, rmseM, prec, rec, f1, events, leads.size(), median(leads),
                share(leads, 10), days == 0 ? 0 : falseEpisodes / days, days);
    }

    record Replay(List<Double> leads, int falseEpisodes) {
    }

    /**
     * Replays one series: a single non-warning reading between two warnings is bridged; a crossing is the margin
     * going from above 1 °C to at or below it after having been back at 3 °C or more; lead = minutes of continuous
     * warning right before it (capped at 60; only crossings with 60 minutes of replay before them count); a false
     * alarm is a warning episode with no crossing within 60 minutes of its start.
     */
    static Replay replay(Scored s) {
        int n = s.examples().size();
        int step = s.stepMinutes();
        boolean[] w = s.alarm().clone();
        for (int i = 1; i + 1 < n; i++) {
            if (!w[i] && w[i - 1] && s.alarm()[i + 1]) {
                w[i] = true;
            }
        }
        double[] m = new double[n];
        for (int i = 0; i < n; i++) {
            m[i] = s.examples().get(i).marginNow();
        }
        List<Integer> crossings = new ArrayList<>();
        boolean armed = false;
        for (int c = 1; c < n; c++) {
            if (m[c - 1] >= 3.0) {
                armed = true;
            }
            if (armed && m[c] <= Examples.CRITICAL_C && m[c - 1] > Examples.CRITICAL_C) {
                armed = false;
                crossings.add(c);
            }
        }
        List<Double> leads = new ArrayList<>();
        int window = LEAD_CAP_MIN / step;
        for (int c : crossings) {
            if (c < window) {
                continue;
            }
            int k = 0;
            while (k < window && c - 1 - k >= 0 && w[c - 1 - k]) {
                k++;
            }
            leads.add((double) k * step);
        }
        int falseEpisodes = 0;
        for (int t = 0; t < n; t++) {
            if (w[t] && (t == 0 || !w[t - 1])) {
                final int start = t;
                if (crossings.stream().noneMatch(c -> c >= start && c <= start + window)) {
                    falseEpisodes++;
                }
            }
        }
        return new Replay(leads, falseEpisodes);
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compare);
        int k = s.size();
        return k % 2 == 1 ? s.get(k / 2) : (s.get(k / 2 - 1) + s.get(k / 2)) / 2;
    }

    static double share(List<Double> v, double atLeast) {
        return v.isEmpty() ? Double.NaN : v.stream().filter(x -> x >= atLeast).count() / (double) v.size();
    }
}

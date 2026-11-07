package edu.neu.ecp.forecast.model;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime forecaster used by the analytics service. One ridge model per horizon predicts the CHANGE in margin
 * (target = margin(t + h) − margin(t)), which generalises better than predicting the absolute value.
 *
 * <p>With fewer than {@link Features#HISTORY_MINUTES} minutes of history it falls back to a linear trend, so
 * a forecast is always available.
 */
public final class MarginForecaster {

    public static final int[] HORIZONS = {15, 30, 60};

    /** Early-warning horizons (minutes) with a classifier each. */
    public static final int[] WARN_HORIZONS = {30, 60};
    /** Margin at or below this is the critical level the early warning predicts. */
    public static final double CRITICAL_C = 1.0;

    private final String version;
    private final Map<Integer, Ridge.LinearModel> models;
    private final Map<Integer, Logistic.Classifier> classifiers;
    private final Map<String, Object> metrics;

    public MarginForecaster(String version, Map<Integer, Ridge.LinearModel> models,
                            Map<Integer, Logistic.Classifier> classifiers, Map<String, Object> metrics) {
        this.version = version;
        this.models = Map.copyOf(models);
        this.classifiers = Map.copyOf(classifiers);
        this.metrics = metrics == null ? Map.of() : new LinkedHashMap<>(metrics);
    }

    public Map<Integer, Logistic.Classifier> classifiers() {
        return classifiers;
    }

    public String version() {
        return version;
    }

    public Map<Integer, Ridge.LinearModel> models() {
        return models;
    }

    public Map<String, Object> metrics() {
        return metrics;
    }

    /** @param perMinute one-minute series, oldest first, ending at "now" */
    public Forecast forecast(List<MarginPoint> perMinute) {
        if (perMinute.isEmpty()) {
            throw new IllegalArgumentException("no history");
        }
        MarginPoint now = perMinute.get(perMinute.size() - 1);
        double m = now.marginC();
        double[] out = new double[HORIZONS.length];
        String method;
        double r30 = 0;
        double r60 = 0;
        boolean w30 = false;
        boolean w60 = false;
        if (perMinute.size() >= Features.HISTORY_MINUTES) {
            double[] x = Features.at(perMinute, perMinute.size() - 1);
            for (int i = 0; i < HORIZONS.length; i++) {
                out[i] = m + models.get(HORIZONS[i]).predict(x);
            }
            Logistic.Classifier c30 = classifiers.get(30);
            Logistic.Classifier c60 = classifiers.get(60);
            r30 = c30.probability(x);
            r60 = c60.probability(x);
            w30 = m > CRITICAL_C && r30 >= c30.threshold();
            w60 = m > CRITICAL_C && r60 >= c60.threshold();
            method = "model";
        } else {
            double slope = trendSlope(perMinute);
            for (int i = 0; i < HORIZONS.length; i++) {
                out[i] = m + slope * HORIZONS[i];
            }
            method = "trend";
            w30 = m > CRITICAL_C && out[1] <= CRITICAL_C;
            w60 = m > CRITICAL_C && out[2] <= CRITICAL_C;
            r30 = w30 ? 1 : 0;
            r60 = w60 ? 1 : 0;
        }
        return new Forecast(out[0], out[1], out[2], condensationAt(now.at(), m, out), r30, r60, w30, w60, method, version);
    }

    /** Least-squares slope of margin per minute over the given points. */
    public static double trendSlope(List<MarginPoint> pts) {
        int n = pts.size();
        if (n < 2) {
            return 0;
        }
        double mx = (n - 1) / 2.0;
        double my = 0;
        for (MarginPoint p : pts) {
            my += p.marginC();
        }
        my /= n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - mx) * (pts.get(i).marginC() - my);
            den += (i - mx) * (i - mx);
        }
        return den == 0 ? 0 : num / den;
    }

    /** First time the piecewise-linear forecast crosses zero, if within the hour. */
    static Instant condensationAt(Instant now, double m0, double[] out) {
        double prevT = 0;
        double prevM = m0;
        for (int i = 0; i < HORIZONS.length; i++) {
            double t = HORIZONS[i];
            double v = out[i];
            if (prevM > 0 && v <= 0) {
                double frac = prevM / (prevM - v);
                double minutes = prevT + frac * (t - prevT);
                return now.plus(Duration.ofSeconds(Math.round(minutes * 60)));
            }
            if (prevM <= 0) {
                return now;
            }
            prevT = t;
            prevM = v;
        }
        return null;
    }
}

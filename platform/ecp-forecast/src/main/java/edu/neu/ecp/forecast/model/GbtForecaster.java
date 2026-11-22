package edu.neu.ecp.forecast.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gradient-boosted-tree version of {@link MarginForecaster}: same features, same horizons, same output. One Huber
 * GBT per horizon predicts the change in margin; one logistic GBT per warning horizon gives the probability that the
 * margin reaches the critical level. With too little history it uses the same linear-trend fallback.
 */
public final class GbtForecaster {

    private final String version;
    private final Map<Integer, Gbt.Model> regressors;
    private final Map<Integer, Gbt.Model> classifiers;
    private final Map<String, Object> metrics;

    public GbtForecaster(String version, Map<Integer, Gbt.Model> regressors, Map<Integer, Gbt.Model> classifiers,
                         Map<String, Object> metrics) {
        for (int h : MarginForecaster.HORIZONS) {
            if (regressors.get(h) == null || regressors.get(h).loss() == Gbt.Loss.LOGISTIC) {
                throw new IllegalArgumentException("missing regression model for " + h + " min");
            }
        }
        for (int h : MarginForecaster.WARN_HORIZONS) {
            if (classifiers.get(h) == null || classifiers.get(h).loss() != Gbt.Loss.LOGISTIC) {
                throw new IllegalArgumentException("missing logistic classifier for " + h + " min");
            }
        }
        this.version = version;
        this.regressors = Map.copyOf(regressors);
        this.classifiers = Map.copyOf(classifiers);
        this.metrics = metrics == null ? Map.of() : new LinkedHashMap<>(metrics);
    }

    public String version() {
        return version;
    }

    public Map<Integer, Gbt.Model> regressors() {
        return regressors;
    }

    public Map<Integer, Gbt.Model> classifiers() {
        return classifiers;
    }

    public Map<String, Object> metrics() {
        return metrics;
    }

    /** Predicted change in margin at each of {@link MarginForecaster#HORIZONS}. */
    public double[] deltas(double[] x) {
        double[] out = new double[MarginForecaster.HORIZONS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = regressors.get(MarginForecaster.HORIZONS[i]).predict(x);
        }
        return out;
    }

    public double risk(int horizon, double[] x) {
        return classifiers.get(horizon).predict(x);
    }

    public boolean warn(int horizon, double[] x, double marginNow) {
        return marginNow > MarginForecaster.CRITICAL_C && risk(horizon, x) >= classifiers.get(horizon).threshold();
    }

    /** @param perMinute one-minute series, oldest first, ending at "now" */
    public Forecast forecast(List<MarginPoint> perMinute) {
        if (perMinute.size() < Features.HISTORY_MINUTES) {
            return MarginForecaster.trendForecast(perMinute, version);
        }
        MarginPoint now = perMinute.get(perMinute.size() - 1);
        double m = now.marginC();
        double[] x = Features.at(perMinute, perMinute.size() - 1);
        double[] d = deltas(x);
        double[] out = {m + d[0], m + d[1], m + d[2]};
        return new Forecast(out[0], out[1], out[2], MarginForecaster.condensationAt(now.at(), m, out),
                risk(30, x), risk(60, x), warn(30, x, m), warn(60, x, m), "model", version);
    }
}

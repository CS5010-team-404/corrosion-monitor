package edu.neu.ecp.forecast.eval;

import edu.neu.ecp.forecast.model.Features;
import edu.neu.ecp.forecast.model.MarginForecaster;
import edu.neu.ecp.forecast.model.MarginPoint;
import java.util.ArrayList;
import java.util.List;

/** Builds {@link Example}s from a one-minute series whose real readings sit every {@code step} minutes. */
public final class Examples {

    public static final double CRITICAL_C = MarginForecaster.CRITICAL_C;
    public static final int MAX_HORIZON = 60;

    private Examples() {
    }

    /**
     * Examples at every real reading (index a multiple of {@code step}, and of {@code stride}) with 30 minutes of
     * history. Readings without 60 minutes of future get {@code future == null} and are used only for replay.
     *
     * @param step minutes between real readings (10 for UCI, 1 for the simulator)
     * @param stride minutes between examples, a multiple of {@code step}
     */
    public static List<Example> of(int location, int series, List<MarginPoint> perMinute, int step, int stride) {
        if (step < 1 || stride % step != 0) {
            throw new IllegalArgumentException("stride must be a multiple of step");
        }
        List<Example> out = new ArrayList<>();
        int first = ((Features.HISTORY_MINUTES - 1 + stride - 1) / stride) * stride;
        for (int t = first; t < perMinute.size(); t += stride) {
            MarginPoint now = perMinute.get(t);
            double m = now.marginC();
            double[] future = null;
            boolean w30 = false;
            boolean w60 = false;
            if (t + MAX_HORIZON < perMinute.size()) {
                future = new double[MarginForecaster.HORIZONS.length];
                for (int i = 0; i < future.length; i++) {
                    future[i] = perMinute.get(t + MarginForecaster.HORIZONS[i]).marginC();
                }
                if (m > CRITICAL_C) {
                    for (int k = t + step; k <= t + MAX_HORIZON; k += step) {
                        if (perMinute.get(k).marginC() <= CRITICAL_C) {
                            w60 = true;
                            if (k - t <= 30) {
                                w30 = true;
                            }
                            break;
                        }
                    }
                }
            }
            double slope = MarginForecaster.trendSlope(perMinute.subList(t - 29, t + 1));
            out.add(new Example(location, series, now.at(), Features.at(perMinute, t), m, future, w30, w60, slope));
        }
        return out;
    }
}

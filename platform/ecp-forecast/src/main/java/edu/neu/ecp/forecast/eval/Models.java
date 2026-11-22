package edu.neu.ecp.forecast.eval;

import edu.neu.ecp.forecast.model.GbtForecaster;
import edu.neu.ecp.forecast.model.Logistic;
import edu.neu.ecp.forecast.model.MarginForecaster;

/** Adapters that let trained forecasters be scored exactly the way production would use them. */
public final class Models {

    private Models() {
    }

    /** The ridge forecaster as production runs it once 31 minutes of history exist. */
    public static Predictor ridge(String name, MarginForecaster f) {
        return new Predictor() {
            public String name() {
                return name;
            }

            public double[] margins(Example e) {
                double[] out = new double[MarginForecaster.HORIZONS.length];
                for (int i = 0; i < out.length; i++) {
                    out[i] = e.marginNow() + f.models().get(MarginForecaster.HORIZONS[i]).predict(e.x());
                }
                return out;
            }

            public boolean warn(Example e, int h) {
                Logistic.Classifier c = f.classifiers().get(h);
                return e.marginNow() > Examples.CRITICAL_C && c.probability(e.x()) >= c.threshold();
            }
        };
    }

    public static Predictor gbt(String name, GbtForecaster f) {
        return new Predictor() {
            public String name() {
                return name;
            }

            public double[] margins(Example e) {
                double[] d = f.deltas(e.x());
                return new double[] {e.marginNow() + d[0], e.marginNow() + d[1], e.marginNow() + d[2]};
            }

            public boolean warn(Example e, int h) {
                return f.warn(h, e.x(), e.marginNow());
            }
        };
    }
}

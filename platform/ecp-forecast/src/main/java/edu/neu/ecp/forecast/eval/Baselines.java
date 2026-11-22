package edu.neu.ecp.forecast.eval;

import edu.neu.ecp.forecast.model.MarginForecaster;

/** The protocol's baselines: persistence, linear trend and the plain 3 °C rule. */
public final class Baselines {

    public static final double RULE_C = 3.0;

    private Baselines() {
    }

    /** "No change": the margin stays where it is. Makes no warnings. */
    public static Predictor persistence() {
        return new Predictor() {
            public String name() {
                return "persistence";
            }

            public double[] margins(Example e) {
                return new double[] {e.marginNow(), e.marginNow(), e.marginNow()};
            }

            public boolean warn(Example e, int h) {
                return false;
            }

            public boolean alarm(Example e) {
                return false;
            }

            public boolean hasWarnings() {
                return false;
            }
        };
    }

    /** Least-squares slope of the last 30 minutes, extrapolated; warns when the extrapolation reaches 1 °C. */
    public static Predictor trend() {
        return new Predictor() {
            public String name() {
                return "trend";
            }

            public double[] margins(Example e) {
                double[] out = new double[MarginForecaster.HORIZONS.length];
                for (int i = 0; i < out.length; i++) {
                    out[i] = e.marginNow() + e.trendSlope() * MarginForecaster.HORIZONS[i];
                }
                return out;
            }

            public boolean warn(Example e, int h) {
                return e.marginNow() > Examples.CRITICAL_C && e.marginNow() + e.trendSlope() * h <= Examples.CRITICAL_C;
            }
        };
    }

    /** What analytics does without ML: warn while 1 °C &lt; margin ≤ 3 °C. No margin forecast. */
    public static Predictor rule() {
        return new Predictor() {
            public String name() {
                return "rule_3C";
            }

            public double[] margins(Example e) {
                return null;
            }

            public boolean warn(Example e, int h) {
                return e.marginNow() > Examples.CRITICAL_C && e.marginNow() <= RULE_C;
            }

            public boolean alarm(Example e) {
                return warn(e, 60);
            }
        };
    }
}

package edu.neu.ecp.forecast.eval;

/**
 * Anything evaluated against real or simulated data: a trained model or a baseline. Margin predictions may be
 * absent (the 3 °C rule) and so may warnings (persistence).
 */
public interface Predictor {

    String name();

    /** Predicted margin at +15/+30/+60 minutes, or null if this predictor makes none. */
    double[] margins(Example e);

    /** Point warning for "critical within h minutes" (h = 30 or 60), or false if it makes none. */
    boolean warn(Example e, int horizon);

    /** Warning as an operator sees it in replay. Default: the production rule, warn60 or margin60 at or below 1 °C. */
    default boolean alarm(Example e) {
        double[] m = margins(e);
        return warn(e, 60) || (m != null && e.marginNow() > Examples.CRITICAL_C && m[2] <= Examples.CRITICAL_C);
    }

    default boolean hasWarnings() {
        return true;
    }
}

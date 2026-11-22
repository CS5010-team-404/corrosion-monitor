package edu.neu.ecp.forecast.eval;

import java.time.Instant;

/**
 * One forecast moment: the features at a real reading and what actually happened afterwards.
 *
 * @param series index of the series (location segment or simulated run) the example comes from
 * @param x the production features ({@code Features.at}), computed from points at or before {@code at} only
 * @param future actual margin at +15/+30/+60 minutes, or null when the series ends before +60 (replay only)
 * @param within30 margin at or below the critical level at any reading in (t, t+30] (false if not {@link #scored})
 * @param trendSlope least-squares margin slope over the last 30 minutes (°C/min), for the trend baseline
 */
public record Example(int location, int series, Instant at, double[] x, double marginNow, double[] future,
                      boolean within30, boolean within60, double trendSlope) {

    /** True when the full 60-minute future is known, so regression and warning metrics can score it. */
    public boolean scored() {
        return future != null;
    }

    public boolean within(int horizon) {
        return horizon == 30 ? within30 : within60;
    }

    /** Moving: |30-minute trend| above 0.02 °C/min, as in the simulated report. */
    public boolean moving() {
        return Math.abs(trendSlope) > 0.02;
    }
}

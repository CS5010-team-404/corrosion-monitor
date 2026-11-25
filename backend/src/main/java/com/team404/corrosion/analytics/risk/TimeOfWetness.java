package com.team404.corrosion.analytics.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Time of wetness (ISO 9223): the time the air is humid and warm enough for a moisture film to form on metal,
 * defined as RH above 80 % while the temperature is above 0 degrees C.
 */
public final class TimeOfWetness {

    public static final double RH_THRESHOLD_PCT = 80.0;
    public static final double TEMP_THRESHOLD_C = 0.0;

    /** One reading. */
    public record Sample(Instant at, double tempC, double rhPct) {
    }

    private TimeOfWetness() {
    }

    public static boolean isWet(double tempC, double rhPct) {
        return rhPct > RH_THRESHOLD_PCT && tempC > TEMP_THRESHOLD_C;
    }

    /**
     * Minutes of wetness in a series of readings sorted by time (oldest first).
     * Each reading counts for the time until the next one, but never longer than {@code maxGap}, so a sensor
     * outage is not counted as wet time we did not measure. The last reading has no "next one" and adds nothing.
     *
     * @throws IllegalArgumentException if {@code maxGap} is null, zero or negative, or the samples are not sorted
     */
    public static double wetMinutes(List<Sample> samples, Duration maxGap) {
        if (maxGap == null || maxGap.isZero() || maxGap.isNegative()) {
            throw new IllegalArgumentException("maxGap must be a positive duration, was " + maxGap);
        }
        double wetSeconds = 0;
        for (int i = 0; i < samples.size() - 1; i++) {
            Sample current = samples.get(i);
            Duration gap = Duration.between(current.at(), samples.get(i + 1).at());
            // Check the order of every pair first, so unsorted input is rejected even when the reading is dry.
            if (gap.isNegative()) {
                throw new IllegalArgumentException("samples must be sorted oldest first");
            }
            if (isWet(current.tempC(), current.rhPct())) {
                wetSeconds += Math.min(gap.getSeconds(), maxGap.getSeconds());
            }
        }
        return wetSeconds / 60.0;
    }
}

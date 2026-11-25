package com.team404.corrosion.analytics;

import java.time.Instant;

/**
 * One row of the platform's {@code sensor_forecasts} table (docs/CONTRACTS.md): the latest condensation-margin
 * forecast of a sensor. Analytics reads it and never calls the platform.
 *
 * @param warningProbability30    0..1, probability the margin reaches the 1 degree C critical level within 30 minutes
 * @param warningProbability60    0..1, the same within 60 minutes
 * @param predictedCondensationAt when the margin is predicted to reach 0 degrees C, or null if not within the hour
 * @param computedAt              when the platform computed it (used to ignore stale rows)
 */
public record SensorForecast(
        double margin15C,
        double margin30C,
        double margin60C,
        double warningProbability30,
        double warningProbability60,
        String method,
        Instant predictedCondensationAt,
        Instant computedAt) {
}

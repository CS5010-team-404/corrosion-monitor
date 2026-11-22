package edu.neu.ecp.platform.forecast;

import edu.neu.ecp.forecast.model.Forecast;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * One row of the shared {@code sensor_forecasts} table (docs/CONTRACTS.md). Values are clamped and rounded to the
 * column types so that a wild extrapolation can never fail an insert under MySQL's strict mode.
 *
 * @param method ML when the trained model produced the forecast, TREND when the history was too short for it
 */
public record PublishedForecast(long sensorId, double margin15C, double margin30C, double margin60C,
                                double warningProbability30, double warningProbability60, String method,
                                Instant predictedCondensationAt, Instant computedAt) {

    /** DECIMAL(6,2) holds ±9999.99; a margin beyond ±999 °C means nothing physically. */
    static final double MARGIN_LIMIT_C = 999.0;

    /** The row for a forecast, or empty if it contains a value that is not a number. */
    public static Optional<PublishedForecast> of(long sensorId, Forecast f, Instant computedAt) {
        double[] values = {f.margin15C(), f.margin30C(), f.margin60C(), f.risk30(), f.risk60()};
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return Optional.empty();
            }
        }
        return Optional.of(new PublishedForecast(sensorId, margin(f.margin15C()), margin(f.margin30C()),
                margin(f.margin60C()), probability(f.risk30()), probability(f.risk60()), method(f.method()),
                f.predictedCondensationAt(), computedAt));
    }

    static String method(String forecastMethod) {
        return switch (forecastMethod == null ? "" : forecastMethod.toLowerCase(Locale.ROOT)) {
            case "model" -> "ML";
            case "trend" -> "TREND";
            default -> throw new IllegalArgumentException("unknown forecast method " + forecastMethod);
        };
    }

    private static double margin(double v) {
        return Math.round(Math.max(-MARGIN_LIMIT_C, Math.min(MARGIN_LIMIT_C, v)) * 100.0) / 100.0;
    }

    private static double probability(double v) {
        return Math.round(Math.max(0.0, Math.min(1.0, v)) * 10_000.0) / 10_000.0;
    }
}

package edu.neu.ecp.forecast.model;

import java.time.Instant;

/**
 * Predicted condensation margin (°C) at 15, 30 and 60 minutes ahead, when it is expected to reach zero (null if
 * not within the hour), and the early-warning probability that it reaches the critical level (1 °C) within 30
 * and 60 minutes. {@code warn30}/{@code warn60} apply the thresholds tuned during training.
 * {@code method} is "model", or "trend" when there is too little history.
 */
public record Forecast(double margin15C, double margin30C, double margin60C, Instant predictedCondensationAt,
                       double risk30, double risk60, boolean warn30, boolean warn60, String method,
                       String modelVersion) {
}

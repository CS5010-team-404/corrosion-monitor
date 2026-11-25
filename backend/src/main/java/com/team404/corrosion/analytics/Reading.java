package com.team404.corrosion.analytics;
import java.time.Instant;

public record Reading(Instant measuredAt, double temperatureC, double humidityPct, Double surfaceTempC) {
}

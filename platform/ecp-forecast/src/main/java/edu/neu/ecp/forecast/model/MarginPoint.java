package edu.neu.ecp.forecast.model;

import edu.neu.ecp.common.domain.Psychrometrics;
import java.time.Instant;

/**
 * One reading as the forecaster sees it: air temperature, humidity and surface temperature at a moment.
 * Dew point and margin are derived with the same Magnus formula the rest of the system uses.
 */
public record MarginPoint(Instant at, double tempC, double humidityPct, double surfaceC) {

    public double dewPointC() {
        return Psychrometrics.dewPointC(tempC, Math.max(0.5, Math.min(100.0, humidityPct)));
    }

    /** Surface temperature minus dew point; at or below zero, water condenses. */
    public double marginC() {
        return surfaceC - dewPointC();
    }
}

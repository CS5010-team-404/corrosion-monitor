package com.team404.corrosion.analytics;

/**
 * One point of GET /api/v1/equipment/{id}/assessments: AssessmentPoint in frontend/src/api/types.ts.
 *
 * @param measuredAt ISO-8601 UTC time of the reading that was assessed
 */
public record AssessmentPoint(
        String measuredAt,
        double temperatureC,
        double humidityPct,
        double surfaceTempC,
        double dewPointC,
        double marginC) {
}

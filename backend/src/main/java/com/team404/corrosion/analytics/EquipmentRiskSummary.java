package com.team404.corrosion.analytics;

/**
 * The risk figures of one equipment item over a period, from the stored assessments: what the report needs.
 * The three percentages are shares of the time the item was assessed (they add up to about 100); time with no
 * trusted reading is not counted. {@code wetHours} is the time of wetness (RH above 80 % at above 0 degrees C), so a
 * location's wet hours are the sum over its items.
 */
public record EquipmentRiskSummary(
        long equipmentId,
        String equipmentName,
        String locationName,
        double normalPct,
        double warningPct,
        double criticalPct,
        double peakRiskIndex,
        double wetHours) {
}

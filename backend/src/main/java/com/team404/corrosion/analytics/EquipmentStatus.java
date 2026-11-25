package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.RiskLevel;

public record EquipmentStatus(
        long equipmentId,
        String equipmentName,
        String locationName,
        String profileName,
        String profileKind,
        RiskLevel riskLevel,
        Double riskIndex,
        Double temperatureC,
        Double humidityPct,
        Double surfaceTempC,
        Double dewPointC,
        Double marginC,
        Double forecastMargin15C,
        Double forecastMargin30C,
        Double forecastMargin60C,
        String predictedCondensationAt,
        String updatedAt,
        double marginWarningC,
        double marginCriticalC,
        double rhWarningPct,
        double rhCriticalPct,
        double tempMinC,
        double tempMaxC,
        double surfaceOffsetC,
        Long sensorId,
        String deviceId,
        boolean trusted,
        Double timeOfWetnessMin24h
) {
}
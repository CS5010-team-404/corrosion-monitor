package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.RiskClassifier;
import java.util.List;

/**
 * Everything analytics needs to assess one equipment item: its profile, its sensor, and the trusted readings
 * from the last 24 hours (oldest first). {@code readings} is empty when the sensor is missing or not trusted.
 */
public record EquipmentSnap(
        long equipmentId,
        String equipmentName,
        String locationName,
        long profileId,
        String profileName,
        String profileKind,
        double tempMinC,
        double tempMaxC,
        double surfaceOffsetC,
        RiskClassifier.Thresholds thresholds,
        double riskBudget,
        double peckN,
        double peckEaEv,
        double refTempC,
        double refRhPct,
        Long sensorId,
        String deviceId,
        boolean trusted,
        List<Reading> readings) {
}

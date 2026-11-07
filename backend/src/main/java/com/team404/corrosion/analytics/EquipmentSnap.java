package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.RiskClassifier;
import java.util.List;

public record EquipmentSnap(
        long equipmentId,
        String equipmentName,
        String locationName,
        long profileId,
        String profileName,
        RiskClassifier.Thresholds thresholds,
        double riskBudget,
        double peckN,
        double peckEaEv,
        double refTempC,
        double refRhPct,
        Long sensorId,
        String deviceId,
        boolean trusted,
        List<Reading>readings)
 {

}

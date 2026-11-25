package com.team404.corrosion.analytics;

import java.time.Instant;
import java.util.List;

public interface AnalyticsRepo {
    List<EquipmentSnap> loadSnapshots(Instant since);
}
package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.RiskClassifier;
import com.team404.corrosion.analytics.risk.RiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsServiceTest{
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final class FakeRepo implements AnalyticsRepo {
        private final List<EquipmentSnap> snapshots;
        Instant askedSince;

        FakeRepo(List<EquipmentSnap> snapshots) {
            this.snapshots = snapshots;
        }

        @Override
        public List<EquipmentSnap> loadSnapshots(Instant since) {
            this.askedSince = since;
            return snapshots;
        }
    }

    private static AnalyticsService serviceWith(FakeRepo repo) {
        return new AnalyticsService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Reading reading(int secondsAgo, double tempC, double rhPct, Double surfaceTempC) {
        return new Reading(NOW.minusSeconds(secondsAgo), tempC, rhPct, surfaceTempC);
    }

    private static EquipmentSnap snapshot(boolean trusted, List<Reading> readings) {
        return new EquipmentSnap(1, "Rack A1", "Server Room A", 1, "IT equipment (ASHRAE)",
                new RiskClassifier.Thresholds(3.0, 1.0, 60.0, 70.0), 100, 2.66, 0.79, 25, 40, 7L, "SIM-001", trusted, readings);
    }

    private static EquipmentStatus onlyStatus(List<Reading> readings) {
        List<EquipmentStatus> result = serviceWith(new FakeRepo(List.of(snapshot(true, readings)))).equipmentStatuses();
        assertEquals(1, result.size());
        return result.get(0);
    }

    @Test
    void computesDewPointAndMarginFromTheLatestReading() {
        EquipmentStatus s = onlyStatus(List.of(reading(60, 25, 50, 20.0), reading(0, 25, 50, 20.0)));
        assertEquals(13.9, s.dewPointC(), 0.1);
        assertEquals(6.1, s.marginC(), 0.1);
        assertEquals(RiskLevel.NORMAL,s.riskLevel());
        assertEquals(NOW.toString(),s.updatedAt());
        assertEquals("it",s.profileKind());
    }
    @Test
    void surfaceTemperatureFallsBackToAirMinusTheColdSpotOffset() {
        EquipmentStatus s = onlyStatus(List.of(reading(0, 25, 50, null)));
        assertEquals(23.5, s.surfaceTempC(), 1e-9);
    }
    @Test
    void criticalWhenTheSurfaceIsBelowTheDewPoint() {
        EquipmentStatus s = onlyStatus(List.of(reading(0, 25, 90, 20.0)));
        assertTrue(s.marginC() < 0);
        assertEquals(RiskLevel.CRITICAL, s.riskLevel());
    }
    @Test
    void warningWhenTheMarginIsShrinking() {
        EquipmentStatus s = onlyStatus(List.of(reading(0, 25, 55, 17.5)));
        assertEquals(RiskLevel.WARNING, s.riskLevel());
    }
    @Test
    void unknownWhenThereAreNoTrustedReadings() {
        EquipmentStatus s = serviceWith(new FakeRepo(List.of(snapshot(false, List.of())))).equipmentStatuses().get(0);
        assertEquals(RiskLevel.UNKNOWN, s.riskLevel());
        assertNull(s.marginC());
        assertNull(s.dewPointC());
        assertNull(s.riskIndex());
        assertFalse(s.trusted());
        assertEquals(1.0, s.marginCriticalC(), 1e-9);
    }
    @Test
    void unknownWhenTheLatestHumidityIsImpossible(){
        EquipmentStatus s = onlyStatus(List.of(reading(0, 25, 0, 20.0)));
        assertEquals(RiskLevel.UNKNOWN, s.riskLevel());
    }
    @Test
    void riskIndexGrowsAsHumidReadingsAccumulate() {
        EquipmentStatus few = onlyStatus(List.of(reading(60, 30, 80, 25.0), reading(0, 30, 80, 25.0)));
        EquipmentStatus many = onlyStatus(List.of(reading(240, 30, 80, 25.0), reading(180, 30, 80, 25.0), reading(120, 30, 80, 25.0),
                reading(60, 30, 80, 25.0), reading(0, 30, 80, 25.0)));
        assertNotNull(few.riskIndex());
        assertTrue(few.riskIndex() > 0);
        assertTrue(many.riskIndex() > few.riskIndex());
    }
    @Test
    void timeOfWetnessCountsMinutesAboveEightyPercent() {
        EquipmentStatus s = onlyStatus(List.of(reading(120, 22, 90, 20.0), reading(60, 22, 90, 20.0), reading(0, 22, 50, 20.0)));
        assertEquals(2.0, s.timeOfWetnessMin24h(), 0.05);
    }

    @Test
    void asksTheRepositoryForTheLast24Hours() {
        FakeRepo repo = new FakeRepo(List.of());
        serviceWith(repo).equipmentStatuses();
        assertEquals(NOW.minusSeconds(24 * 3600), repo.askedSince);
    }

    @Test
    void forecastFieldsAreNotFilledInYet() {
        EquipmentStatus s = onlyStatus(List.of(reading(0, 25, 50, 20.0)));
        assertNull(s.forecastMargin15C());
        assertNull(s.predictedCondensationAt());
    }

}
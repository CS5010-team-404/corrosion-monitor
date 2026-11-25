package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.RiskClassifier;
import com.team404.corrosion.analytics.risk.RiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsServiceTest {
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

    private final FakeAssessmentRepo assessments = new FakeAssessmentRepo();

    private AnalyticsService serviceWith(FakeRepo repo) {
        return new AnalyticsService(repo, assessments, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Reading reading(int secondsAgo, double tempC, double rhPct, Double surfaceTempC) {
        return new Reading(NOW.minusSeconds(secondsAgo), tempC, rhPct, surfaceTempC);
    }

    private static SensorForecast forecast(int computedSecondsAgo, double probability60, Instant predictedAt) {
        return new SensorForecast(3.5, 2.5, 1.25, 0.2, probability60, "ML", predictedAt,
                NOW.minusSeconds(computedSecondsAgo));
    }

    private static EquipmentSnap snapshot(boolean trusted, List<Reading> readings) {
        return snapshot("it", 18, 27, 1.5, trusted, readings);
    }

    private static EquipmentSnap snapshot(String kind, double tempMinC, double tempMaxC, double surfaceOffsetC,
                                          boolean trusted, List<Reading> readings) {
        return snap(1, kind, tempMinC, tempMaxC, surfaceOffsetC, 100, trusted, readings, null);
    }

    private static EquipmentSnap snap(long equipmentId, String kind, double tempMinC, double tempMaxC,
                                      double surfaceOffsetC, double riskBudget, boolean trusted, List<Reading> readings,
                                      SensorForecast forecast) {
        return new EquipmentSnap(equipmentId, "Rack " + equipmentId, "Server Room A", 1, "IT equipment (ASHRAE)",
                kind, tempMinC, tempMaxC, surfaceOffsetC,
                new RiskClassifier.Thresholds(3.0, 1.0, 60.0, 70.0), riskBudget, 2.66, 0.79, 25, 40, 7L, "SIM-001",
                trusted, readings, forecast);
    }

    private EquipmentStatus onlyStatus(List<Reading> readings) {
        List<EquipmentStatus> result = serviceWith(new FakeRepo(List.of(snapshot(true, readings)))).equipmentStatuses();
        assertEquals(1, result.size());
        return result.get(0);
    }

    private AnalyticsService.Evaluation onlyEvaluation(EquipmentSnap snap) {
        List<AnalyticsService.Evaluation> result = serviceWith(new FakeRepo(List.of(snap))).evaluateAll();
        assertEquals(1, result.size());
        return result.get(0);
    }

    @Test
    void computesDewPointAndMarginFromTheLatestReading() {
        EquipmentStatus s = onlyStatus(List.of(reading(60, 25, 50, 20.0), reading(0, 25, 50, 20.0)));
        assertEquals(13.9, s.dewPointC(), 0.1);
        assertEquals(6.1, s.marginC(), 0.1);
        assertEquals(RiskLevel.NORMAL, s.riskLevel());
        assertEquals(NOW.toString(), s.updatedAt());
        assertEquals("it", s.profileKind());
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
    void unknownWhenTheLatestHumidityIsImpossible() {
        EquipmentStatus s = onlyStatus(List.of(reading(0, 25, 0, 20.0)));
        assertEquals(RiskLevel.UNKNOWN, s.riskLevel());
    }

    @Test
    void riskIndexGrowsAsHumidReadingsAccumulate() {
        EquipmentStatus few = onlyStatus(List.of(reading(60, 30, 80, 25.0), reading(0, 30, 80, 25.0)));
        EquipmentStatus many = onlyStatus(List.of(reading(240, 30, 80, 25.0), reading(180, 30, 80, 25.0),
                reading(120, 30, 80, 25.0), reading(60, 30, 80, 25.0), reading(0, 30, 80, 25.0)));
        assertNotNull(few.riskIndex());
        assertTrue(few.riskIndex() > 0);
        assertTrue(many.riskIndex() > few.riskIndex());
    }

    @Test
    void timeOfWetnessCountsMinutesAboveEightyPercent() {
        EquipmentStatus s = onlyStatus(List.of(reading(120, 22, 90, 20.0), reading(60, 22, 90, 20.0),
                reading(0, 22, 50, 20.0)));
        assertEquals(2.0, s.timeOfWetnessMin24h(), 0.05);
    }

    @Test
    void asksTheRepositoryForTheLast24Hours() {
        FakeRepo repo = new FakeRepo(List.of());
        serviceWith(repo).equipmentStatuses();
        assertEquals(NOW.minusSeconds(24 * 3600), repo.askedSince);
    }

    @Test
    void profileDataComesFromTheSnapshotNotFromJava() {
        // a plc profile with a 3 C cold-spot offset: the surface temperature must use that offset
        EquipmentSnap plc = snapshot("plc", 0, 55, 3.0, true, List.of(reading(0, 25, 50, null)));
        EquipmentStatus s = serviceWith(new FakeRepo(List.of(plc))).equipmentStatuses().get(0);
        assertEquals("plc", s.profileKind());
        assertEquals(0.0, s.tempMinC(), 1e-9);
        assertEquals(55.0, s.tempMaxC(), 1e-9);
        assertEquals(3.0, s.surfaceOffsetC(), 1e-9);
        assertEquals(22.0, s.surfaceTempC(), 1e-9);    // 25 - 3.0
    }

    @Test
    void unknownStatusStillReportsTheProfileData() {
        EquipmentSnap noSensor = snapshot("tel", 5, 40, 1.5, false, List.of());
        EquipmentStatus s = serviceWith(new FakeRepo(List.of(noSensor))).equipmentStatuses().get(0);
        assertEquals(RiskLevel.UNKNOWN, s.riskLevel());
        assertEquals("tel", s.profileKind());
        assertEquals(40.0, s.tempMaxC(), 1e-9);
    }

    // ----------------------------------------------------------------------------------------------- forecast

    @Test
    void forecastFieldsStayEmptyWithoutAForecastRow() {
        AnalyticsService.Evaluation e = onlyEvaluation(snapshot(true, List.of(reading(0, 25, 50, 20.0))));
        assertNull(e.status().forecastMargin15C());
        assertNull(e.status().forecastMargin30C());
        assertNull(e.status().forecastMargin60C());
        assertNull(e.status().predictedCondensationAt());
        assertNull(e.warningProbability60());
    }

    @Test
    void aFreshForecastFillsTheStatus() {
        Instant predicted = NOW.plusSeconds(20 * 60);
        EquipmentSnap snap = snap(1, "it", 18, 27, 1.5, 100, true, List.of(reading(0, 25, 50, 20.0)),
                forecast(60, 0.62, predicted));

        AnalyticsService.Evaluation e = onlyEvaluation(snap);

        assertEquals(3.5, e.status().forecastMargin15C(), 1e-9);
        assertEquals(2.5, e.status().forecastMargin30C(), 1e-9);
        assertEquals(1.25, e.status().forecastMargin60C(), 1e-9);
        assertEquals(predicted.toString(), e.status().predictedCondensationAt());
        assertEquals(0.62, e.warningProbability60(), 1e-9);
    }

    @Test
    void aForecastWithoutAPredictedTimeLeavesThatFieldNull() {
        EquipmentSnap snap = snap(1, "it", 18, 27, 1.5, 100, true, List.of(reading(0, 25, 50, 20.0)),
                forecast(60, 0.1, null));
        AnalyticsService.Evaluation e = onlyEvaluation(snap);
        assertEquals(1.25, e.status().forecastMargin60C(), 1e-9);
        assertNull(e.status().predictedCondensationAt());
    }

    @Test
    void aForecastOlderThanFiveMinutesIsIgnored() {
        EquipmentSnap stale = snap(1, "it", 18, 27, 1.5, 100, true, List.of(reading(0, 25, 50, 20.0)),
                forecast(5 * 60 + 1, 0.9, NOW.plusSeconds(600)));
        AnalyticsService.Evaluation e = onlyEvaluation(stale);
        assertNull(e.status().forecastMargin15C());
        assertNull(e.status().predictedCondensationAt());
        assertNull(e.warningProbability60());
        assertEquals(RiskLevel.NORMAL, e.status().riskLevel());   // the rest of the status is unaffected
    }

    @Test
    void aForecastExactlyFiveMinutesOldIsStillUsed() {
        EquipmentSnap edge = snap(1, "it", 18, 27, 1.5, 100, true, List.of(reading(0, 25, 50, 20.0)),
                forecast(5 * 60, 0.9, null));
        assertEquals(0.9, onlyEvaluation(edge).warningProbability60(), 1e-9);
    }

    @Test
    void noForecastIsReportedWhenThereIsNoTrustedReading() {
        EquipmentSnap noReadings = snap(1, "it", 18, 27, 1.5, 100, false, List.of(), forecast(10, 0.9, null));
        AnalyticsService.Evaluation e = onlyEvaluation(noReadings);
        assertEquals(RiskLevel.UNKNOWN, e.status().riskLevel());
        assertNull(e.warningProbability60());
    }

    // ----------------------------------------------------------------------------------------------- robustness

    @Test
    void oneBrokenItemIsUnknownAndTheOthersStillWork() {
        EquipmentSnap broken = snap(1, "it", 18, 27, 1.5, 0, true, List.of(reading(0, 25, 50, 20.0)), null);  // budget 0
        EquipmentSnap fine = snap(2, "it", 18, 27, 1.5, 100, true, List.of(reading(0, 25, 50, 20.0)), null);

        List<EquipmentStatus> result = serviceWith(new FakeRepo(List.of(broken, fine))).equipmentStatuses();

        assertEquals(2, result.size());
        assertEquals(RiskLevel.UNKNOWN, result.get(0).riskLevel());
        assertEquals(1L, result.get(0).equipmentId());
        assertEquals(RiskLevel.NORMAL, result.get(1).riskLevel());
    }

    // ----------------------------------------------------------------------------------------------- assessments

    @Test
    void assessmentsOfAnUnknownEquipmentItemAreEmpty() {
        assertEquals(Optional.empty(), serviceWith(new FakeRepo(List.of())).assessments(99, 60));
    }

    @Test
    void assessmentsAskForTheRequestedNumberOfMinutes() {
        assessments.knownEquipment.add(5L);
        AssessmentPoint point = new AssessmentPoint(NOW.toString(), 25, 50, 20, 13.85, 6.15);
        assessments.pointsToReturn = List.of(point);

        Optional<List<AssessmentPoint>> result = serviceWith(new FakeRepo(List.of())).assessments(5, 30);

        assertEquals(List.of(point), result.orElseThrow());
        assertEquals(5L, assessments.pointsFor);
        assertEquals(NOW.minusSeconds(30 * 60), assessments.pointsSince);
    }

    @Test
    void assessmentMinutesAreCutToBetweenOneMinuteAndSevenDays() {
        assessments.knownEquipment.add(5L);
        AnalyticsService service = serviceWith(new FakeRepo(List.of()));

        service.assessments(5, 0);
        assertEquals(NOW.minusSeconds(60), assessments.pointsSince);
        service.assessments(5, -20);
        assertEquals(NOW.minusSeconds(60), assessments.pointsSince);
        service.assessments(5, 100_000);
        assertEquals(NOW.minusSeconds(7 * 24 * 3600), assessments.pointsSince);
    }

    // ----------------------------------------------------------------------------------------------- risk summary

    @Test
    void riskSummaryPassesThePeriodAndLocationAndCalculatesFromTheStoredRows() {
        assessments.summary = List.of(new AssessmentRepo.SummaryRow(4, "Core switch", "Telecom Closet 2",
                NOW.minusSeconds(120), "NORMAL", 0.01, 22, 50));
        Instant from = NOW.minusSeconds(3600);

        List<EquipmentRiskSummary> result = serviceWith(new FakeRepo(List.of()))
                .riskSummary(from, NOW.plusSeconds(3600), "Telecom Closet 2");

        assertEquals(from, assessments.summaryFrom);
        assertEquals(NOW.plusSeconds(3600), assessments.summaryTo);
        assertEquals("Telecom Closet 2", assessments.summaryLocation);
        assertEquals(1, result.size());
        assertEquals(4L, result.get(0).equipmentId());
        assertEquals(100.0, result.get(0).normalPct(), 1e-9);   // the end of the period is capped at now
    }

    @Test
    void riskSummaryWithoutALocationCoversEveryLocation() {
        serviceWith(new FakeRepo(List.of())).riskSummary(null, null);
        assertNull(assessments.summaryLocation);
        assertNull(assessments.summaryFrom);
        assertNull(assessments.summaryTo);
    }
}

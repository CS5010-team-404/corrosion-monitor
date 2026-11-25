package com.team404.corrosion.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskSummaryCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

    private static AssessmentRepo.SummaryRow row(long equipmentId, int secondsAfterT0, String level, double riskIndex,
                                                 double tempC, double rhPct) {
        return new AssessmentRepo.SummaryRow(equipmentId, "Rack " + equipmentId, "Room " + equipmentId,
                T0.plusSeconds(secondsAfterT0), level, riskIndex, tempC, rhPct);
    }

    private static Instant at(int secondsAfterT0) {
        return T0.plusSeconds(secondsAfterT0);
    }

    @Test
    void noRowsGiveNoSummaries() {
        assertTrue(RiskSummaryCalculator.summarize(List.of(), at(600)).isEmpty());
    }

    @Test
    void timeIsSharedByTheLevelEachAssessmentStandsFor() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "NORMAL", 0.1, 20, 50),
                row(1, 60, "WARNING", 0.2, 20, 50),
                row(1, 120, "CRITICAL", 0.3, 20, 50)), at(180));   // 60 s at each level

        assertEquals(1, result.size());
        EquipmentRiskSummary s = result.get(0);
        assertEquals(33.3, s.normalPct(), 1e-9);
        assertEquals(33.3, s.warningPct(), 1e-9);
        assertEquals(33.3, s.criticalPct(), 1e-9);
    }

    @Test
    void unevenTimeGivesUnevenShares() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "NORMAL", 0.1, 20, 50),        // 180 s
                row(1, 180, "WARNING", 0.2, 20, 50)),    // 60 s until the end
                at(240));

        assertEquals(75.0, result.get(0).normalPct(), 1e-9);
        assertEquals(25.0, result.get(0).warningPct(), 1e-9);
        assertEquals(0.0, result.get(0).criticalPct(), 1e-9);
    }

    @Test
    void aGapIsNotCountedAsTimeAtAnyLevel() {
        // NORMAL, then nothing for an hour, then WARNING: the gap counts for at most five minutes
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "NORMAL", 0.1, 20, 50),
                row(1, 3600, "WARNING", 0.2, 20, 50)), at(3660));

        assertEquals(83.3, result.get(0).normalPct(), 1e-9);    // 300 s normal
        assertEquals(16.7, result.get(0).warningPct(), 1e-9);   // 60 s warning
    }

    @Test
    void theLastAssessmentCountsUntilTheEndButNotMoreThanFiveMinutes() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "NORMAL", 0.1, 20, 90)), at(3600));

        assertEquals(100.0, result.get(0).normalPct(), 1e-9);
        assertEquals(RiskSummaryCalculator.MAX_SPAN.toSeconds() / 3600.0, result.get(0).wetHours(), 0.01);
    }

    @Test
    void aSingleAssessmentWithNoTimeAfterItReportsItsLevel() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "CRITICAL", 0.9, 20, 50)), at(0));

        assertEquals(0.0, result.get(0).normalPct(), 1e-9);
        assertEquals(0.0, result.get(0).warningPct(), 1e-9);
        assertEquals(100.0, result.get(0).criticalPct(), 1e-9);
    }

    @Test
    void thePeakRiskIndexIsTheLargestOne() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "NORMAL", 0.10, 20, 50),
                row(1, 60, "NORMAL", 0.75, 20, 50),
                row(1, 120, "NORMAL", 0.40, 20, 50)), at(180));

        assertEquals(0.75, result.get(0).peakRiskIndex(), 1e-9);
    }

    @Test
    void wetHoursCountTheTimeAboveEightyPercentAndAboveZeroDegrees() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "NORMAL", 0.1, 20, 90),     // wet, 60 s
                row(1, 60, "NORMAL", 0.1, 20, 50),    // dry
                row(1, 120, "NORMAL", 0.1, -5, 95),   // too cold to count
                row(1, 180, "NORMAL", 0.1, 20, 85)),  // wet, 60 s until the end
                at(240));

        assertEquals(120 / 3600.0, result.get(0).wetHours(), 0.01);
    }

    @Test
    void eachEquipmentItemGetsItsOwnSummaryInTheOrderGiven() {
        List<EquipmentRiskSummary> result = RiskSummaryCalculator.summarize(List.of(
                row(2, 0, "NORMAL", 0.1, 20, 50),
                row(2, 60, "NORMAL", 0.1, 20, 50),
                row(7, 0, "WARNING", 0.5, 20, 50),
                row(7, 60, "WARNING", 0.6, 20, 50)), at(120));

        assertEquals(2, result.size());
        assertEquals(2L, result.get(0).equipmentId());
        assertEquals("Rack 2", result.get(0).equipmentName());
        assertEquals("Room 2", result.get(0).locationName());
        assertEquals(100.0, result.get(0).normalPct(), 1e-9);
        assertEquals(7L, result.get(1).equipmentId());
        assertEquals(100.0, result.get(1).warningPct(), 1e-9);
        assertEquals(0.6, result.get(1).peakRiskIndex(), 1e-9);
    }

    @Test
    void anUnexpectedLevelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RiskSummaryCalculator.summarize(List.of(
                row(1, 0, "UNKNOWN", 0.1, 20, 50)), at(60)));
    }
}

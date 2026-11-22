package com.team404.corrosion.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team404.corrosion.report.ReportCalculator.AlertFacts;
import com.team404.corrosion.report.ReportSummary.EquipmentRisk;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");

    @Test
    void countsAlertsBySeverityAndAveragesResponseTimes() {
        List<AlertFacts> alerts = List.of(
                new AlertFacts("CRITICAL", T0, T0.plus(min(6)), T0.plus(min(20))),
                new AlertFacts("WARNING", T0, T0.plus(min(10)), null),
                new AlertFacts("WARNING", T0, null, T0.plus(min(30))),
                new AlertFacts("WARNING", T0, null, null));

        ReportSummary r = ReportCalculator.summarize(period(), alerts, 1, List.of(), List.of());

        assertThat(r.alertsOpened()).isEqualTo(4);
        assertThat(r.alertsBySeverity()).containsExactly(
                java.util.Map.entry("WARNING", 3L), java.util.Map.entry("CRITICAL", 1L));
        assertThat(r.meanMinutesToAcknowledge()).isEqualTo(8.0);   // (6 + 10) / 2: only acknowledged alerts count
        assertThat(r.meanMinutesToResolve()).isEqualTo(25.0);      // (20 + 30) / 2
        assertThat(r.escalations()).isEqualTo(1);
    }

    @Test
    void meansAreRoundedToOneDecimalAndNullWhenNothingQualifies() {
        List<AlertFacts> alerts = List.of(
                new AlertFacts("WARNING", T0, T0.plusSeconds(100), null),
                new AlertFacts("WARNING", T0, T0.plusSeconds(200), null),
                new AlertFacts("WARNING", T0, T0.plusSeconds(200), null));

        assertThat(ReportCalculator.meanMinutes(alerts, AlertFacts::acknowledgedAt)).isEqualTo(2.8); // 166.7 s
        assertThat(ReportCalculator.meanMinutes(alerts, AlertFacts::resolvedAt)).isNull();
        assertThat(ReportCalculator.meanMinutes(List.of(), AlertFacts::acknowledgedAt)).isNull();
    }

    @Test
    void anAcknowledgementBeforeTheOpeningCountsAsZero() {
        var skewed = List.of(new AlertFacts("WARNING", T0, T0.minusSeconds(30), null));
        assertThat(ReportCalculator.meanMinutes(skewed, AlertFacts::acknowledgedAt)).isEqualTo(0.0);
    }

    @Test
    void anEmptyPeriodHasZerosBothSeveritiesAndNoMeans() {
        ReportSummary r = ReportCalculator.summarize(ReportPeriod.allTime(), List.of(), 0, List.of(), List.of());

        assertThat(r.from()).isNull();
        assertThat(r.to()).isNull();
        assertThat(r.location()).isNull();
        assertThat(r.alertsOpened()).isZero();
        assertThat(r.alertsBySeverity()).containsOnlyKeys("WARNING", "CRITICAL").containsValues(0L);
        assertThat(r.meanMinutesToAcknowledge()).isNull();
        assertThat(r.meanMinutesToResolve()).isNull();
        assertThat(r.correctiveActions()).isZero();
        assertThat(r.correctiveActionsByType()).isEmpty();
        assertThat(r.equipment()).isEmpty();
    }

    @Test
    void correctiveActionsAreCountedByTypeMostFrequentFirst() {
        ReportSummary r = ReportCalculator.summarize(period(), List.of(), 0,
                List.of("NOTE", "DEHUMIDIFIER_ON", "RESOLVED", "DEHUMIDIFIER_ON", "INSPECTED", "NOTE", "DEHUMIDIFIER_ON"),
                List.of());

        assertThat(r.correctiveActions()).isEqualTo(7);
        assertThat(r.correctiveActionsByType().keySet())
                .containsExactly("DEHUMIDIFIER_ON", "NOTE", "INSPECTED", "RESOLVED");  // ties by name
        assertThat(r.correctiveActionsByType().get("DEHUMIDIFIER_ON")).isEqualTo(3);
    }

    @Test
    void carriesThePeriodAndTheRiskFigures() {
        var risk = List.of(new EquipmentRisk(1, "Rack A1", "Server Room A", 90, 8, 2));
        ReportSummary r = ReportCalculator.summarize(period(), List.of(), 0, List.of(), risk);

        assertThat(r.from()).isEqualTo("2026-11-10T00:00:00Z");
        assertThat(r.to()).isEqualTo("2026-11-11T00:00:00Z");
        assertThat(r.location()).isEqualTo("Server Room A");
        assertThat(r.equipment()).isEqualTo(risk);
    }

    @Test
    void thePeriodIsHalfOpenAndValidated() {
        var p = period();
        assertThat(p.contains(Instant.parse("2026-11-10T00:00:00Z"))).isTrue();
        assertThat(p.contains(Instant.parse("2026-11-10T23:59:59Z"))).isTrue();
        assertThat(p.contains(Instant.parse("2026-11-11T00:00:00Z"))).isFalse();
        assertThat(p.contains(null)).isFalse();
        assertThat(ReportPeriod.allTime().contains(T0)).isTrue();
        assertThat(new ReportPeriod(null, null, " all ").location()).isNull();
        assertThat(new ReportPeriod(null, null, "  ").location()).isNull();
        assertThatThrownBy(() -> new ReportPeriod(T0, T0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportPeriod(T0, T0.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReportPeriod period() {
        return new ReportPeriod(Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-11T00:00:00Z"),
                "Server Room A");
    }

    private static Duration min(long minutes) {
        return Duration.ofMinutes(minutes);
    }
}

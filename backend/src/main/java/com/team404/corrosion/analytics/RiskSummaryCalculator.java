package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.TimeOfWetness;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The time each equipment item spent in each risk level, its peak risk index and its wet hours, from stored
 * assessments. Plain Java, no database.
 *
 * <p>An assessment counts for the time until the next one, but never longer than {@link #MAX_SPAN}, so a gap (the job
 * or the sensor was down) is not counted as time at any level. The last assessment counts until {@code end}, also
 * capped. The three shares are of the assessed time, not of the whole period.
 */
final class RiskSummaryCalculator {

    /** Longest time one assessment stands for. The job runs every minute, so five minutes is a generous gap. */
    static final Duration MAX_SPAN = Duration.ofMinutes(5);

    private RiskSummaryCalculator() {
    }

    /**
     * @param rows rows ordered by equipment and then by time, as {@link AssessmentRepo#summaryRows} returns them
     * @param end  where the last assessment of each item stops counting (the end of the period, or now)
     */
    static List<EquipmentRiskSummary> summarize(List<AssessmentRepo.SummaryRow> rows, Instant end) {
        List<EquipmentRiskSummary> result = new ArrayList<>();
        int start = 0;
        while (start < rows.size()) {
            int stop = start;
            while (stop < rows.size() && rows.get(stop).equipmentId() == rows.get(start).equipmentId()) {
                stop++;
            }
            result.add(summarizeItem(rows.subList(start, stop), end));
            start = stop;
        }
        return result;
    }

    private static EquipmentRiskSummary summarizeItem(List<AssessmentRepo.SummaryRow> rows, Instant end) {
        double normal = 0;
        double warning = 0;
        double critical = 0;
        double wet = 0;
        double peak = 0;
        for (int i = 0; i < rows.size(); i++) {
            AssessmentRepo.SummaryRow row = rows.get(i);
            Instant until = i + 1 < rows.size() ? rows.get(i + 1).assessedAt() : end;
            double seconds = span(row.assessedAt(), until);
            switch (row.level()) {
                case "NORMAL" -> normal += seconds;
                case "WARNING" -> warning += seconds;
                case "CRITICAL" -> critical += seconds;
                default -> throw new IllegalArgumentException("unexpected level " + row.level());
            }
            if (TimeOfWetness.isWet(row.temperatureC(), row.rhPct())) {
                wet += seconds;
            }
            peak = Math.max(peak, row.riskIndex());
        }
        double total = normal + warning + critical;
        if (total == 0) {
            // a single assessment with no time after it yet: report the level it is in
            String level = rows.get(rows.size() - 1).level();
            normal = level.equals("NORMAL") ? 1 : 0;
            warning = level.equals("WARNING") ? 1 : 0;
            critical = level.equals("CRITICAL") ? 1 : 0;
            total = 1;
        }
        AssessmentRepo.SummaryRow first = rows.get(0);
        return new EquipmentRiskSummary(first.equipmentId(), first.equipmentName(), first.locationName(),
                round(100 * normal / total, 1), round(100 * warning / total, 1), round(100 * critical / total, 1),
                peak, round(wet / 3600.0, 2));
    }

    private static double span(Instant from, Instant until) {
        long seconds = Duration.between(from, until).getSeconds();
        return Math.max(0, Math.min(seconds, MAX_SPAN.getSeconds()));
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}

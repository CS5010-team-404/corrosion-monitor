package com.team404.corrosion.report;

import com.team404.corrosion.report.ReportSummary.EquipmentRisk;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The report's numbers from plain facts, so they can be tested without a database.
 *
 * <ul>
 *   <li>Alert figures cover the alerts opened in the period. The response times of those alerts count even when the
 *       acknowledgement or resolution came after the period ended.</li>
 *   <li>Escalations and corrective actions count when they happened, so the caller passes only those in the period.</li>
 * </ul>
 * Means are in minutes, rounded to one decimal, and null when nothing was acknowledged (or resolved).
 */
public final class ReportCalculator {

    static final List<String> SEVERITIES = List.of("WARNING", "CRITICAL");

    private ReportCalculator() {
    }

    /** One alert opened in the period. acknowledgedAt and resolvedAt are null until that happens. */
    public record AlertFacts(String severity, Instant openedAt, Instant acknowledgedAt, Instant resolvedAt) {
        public AlertFacts {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(openedAt, "openedAt");
        }
    }

    public static ReportSummary summarize(ReportPeriod period, List<AlertFacts> alerts, long escalations,
                                          List<String> actionTypes, List<EquipmentRisk> equipment) {
        return new ReportSummary(
                period.from() == null ? null : period.from().toString(),
                period.to() == null ? null : period.to().toString(),
                alerts.size(),
                meanMinutes(alerts, AlertFacts::acknowledgedAt),
                actionTypes.size(),
                List.copyOf(equipment),
                period.location(),
                bySeverity(alerts),
                meanMinutes(alerts, AlertFacts::resolvedAt),
                escalations,
                byType(actionTypes));
    }

    /** Mean minutes from opening to the given moment, over the alerts that have it. Negative gaps (clock skew) count as 0. */
    static Double meanMinutes(List<AlertFacts> alerts, Function<AlertFacts, Instant> moment) {
        double[] minutes = alerts.stream()
                .filter(a -> moment.apply(a) != null)
                .mapToDouble(a -> Math.max(0, Duration.between(a.openedAt(), moment.apply(a)).toMillis()) / 60_000.0)
                .toArray();
        if (minutes.length == 0) {
            return null;
        }
        double sum = 0;
        for (double m : minutes) {
            sum += m;
        }
        return Math.round(sum / minutes.length * 10) / 10.0;
    }

    /** WARNING and CRITICAL always appear (0 if none); any other severity is listed after them. */
    static Map<String, Long> bySeverity(List<AlertFacts> alerts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        SEVERITIES.forEach(s -> counts.put(s, 0L));
        alerts.forEach(a -> counts.merge(a.severity(), 1L, Long::sum));
        return counts;
    }

    /** Most frequent first, ties by name, so the report reads the same every time. */
    static Map<String, Long> byType(List<String> actionTypes) {
        return actionTypes.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}

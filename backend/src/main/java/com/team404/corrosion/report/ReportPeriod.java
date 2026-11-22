package com.team404.corrosion.report;

import java.time.Instant;

/**
 * What a report covers: the half-open interval [from, to) in UTC (either end may be open) and, optionally, one
 * location by name. An event counts when it happened inside the interval.
 */
public record ReportPeriod(Instant from, Instant to, String location) {

    public ReportPeriod {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("'from' must be before 'to'.");
        }
        location = location == null || location.isBlank() || "all".equalsIgnoreCase(location.trim())
                ? null : location.trim();
    }

    public static ReportPeriod allTime() {
        return new ReportPeriod(null, null, null);
    }

    public boolean contains(Instant t) {
        return t != null && (from == null || !t.isBefore(from)) && (to == null || t.isBefore(to));
    }
}

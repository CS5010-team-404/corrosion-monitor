package com.team404.corrosion.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The stored assessments (table risk_assessments, kept for 7 days). */
public interface AssessmentRepo {

    /** One evaluation to store; the level is NORMAL, WARNING or CRITICAL. */
    record Row(long equipmentId, Instant assessedAt, Instant measuredAt, double temperatureC, double surfaceTempC,
               double marginC, double dewPointC, double rhPct, double riskIndex, String level) {
    }

    /** What the risk summary needs of one stored row. */
    record SummaryRow(long equipmentId, String equipmentName, String locationName, Instant assessedAt, String level,
                      double riskIndex, double temperatureC, double rhPct) {
    }

    /** The newest reading time already stored for each equipment item that has any. */
    Map<Long, Instant> lastMeasuredAt();

    void insert(List<Row> rows);

    /** Deletes the assessments evaluated before the cutoff. Returns how many. */
    int deleteBefore(Instant cutoff);

    boolean equipmentExists(long equipmentId);

    /** The points of one item evaluated at or after {@code since}, oldest first. */
    List<AssessmentPoint> points(long equipmentId, Instant since);

    /**
     * Stored rows evaluated in [from, to) (either may be null for open), at the location if one is given (by name),
     * ordered by equipment and time.
     */
    List<SummaryRow> summaryRows(Instant from, Instant to, String location);
}

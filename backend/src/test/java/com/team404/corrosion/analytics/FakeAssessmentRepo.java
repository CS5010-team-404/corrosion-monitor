package com.team404.corrosion.analytics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An in-memory stand-in for the stored assessments, for unit tests. It records what it was asked. */
public class FakeAssessmentRepo implements AssessmentRepo {

    public final Map<Long, Instant> lastMeasured = new HashMap<>();
    public final List<Row> inserted = new ArrayList<>();
    public final Set<Long> knownEquipment = new HashSet<>();
    public Instant prunedBefore;
    public int pruneCount;
    public RuntimeException failOnInsert;

    public List<AssessmentPoint> pointsToReturn = List.of();
    public Long pointsFor;
    public Instant pointsSince;

    public List<SummaryRow> summary = List.of();
    public Instant summaryFrom;
    public Instant summaryTo;
    public String summaryLocation;

    @Override
    public Map<Long, Instant> lastMeasuredAt() {
        return lastMeasured;
    }

    @Override
    public void insert(List<Row> rows) {
        if (failOnInsert != null) {
            throw failOnInsert;
        }
        inserted.addAll(rows);
        rows.forEach(r -> lastMeasured.merge(r.equipmentId(), r.measuredAt(),
                (a, b) -> a.isAfter(b) ? a : b));
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        prunedBefore = cutoff;
        return pruneCount;
    }

    @Override
    public boolean equipmentExists(long equipmentId) {
        return knownEquipment.contains(equipmentId);
    }

    @Override
    public List<AssessmentPoint> points(long equipmentId, Instant since) {
        pointsFor = equipmentId;
        pointsSince = since;
        return pointsToReturn;
    }

    @Override
    public List<SummaryRow> summaryRows(Instant from, Instant to, String location) {
        summaryFrom = from;
        summaryTo = to;
        summaryLocation = location;
        return summary;
    }
}

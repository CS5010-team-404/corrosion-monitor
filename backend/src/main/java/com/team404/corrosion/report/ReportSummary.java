package com.team404.corrosion.report;

import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/reports/summary. The first six fields are ReportSummary in frontend/src/api/types.ts; the rest are the
 * FR7 breakdowns the CSV and PDF also show (the UI can start using them whenever it likes).
 *
 * @param from                     start of the period (ISO-8601 UTC), null for open
 * @param to                       end of the period, exclusive, null for open
 * @param alertsOpened             alerts opened in the period
 * @param meanMinutesToAcknowledge mean opened → acknowledged, over those alerts that were acknowledged; null if none
 * @param correctiveActions        corrective actions logged in the period
 * @param equipment                time in each risk level per equipment item (analytics); empty until it is wired in
 * @param location                 the location filter, null for all locations
 * @param alertsBySeverity         alerts opened in the period by severity (WARNING, CRITICAL; always both keys)
 * @param meanMinutesToResolve     mean opened → resolved, over those alerts that were resolved; null if none
 * @param escalations              alerts escalated to the managers in the period
 * @param correctiveActionsByType  corrective actions logged in the period by type, most frequent first
 */
public record ReportSummary(
        String from,
        String to,
        long alertsOpened,
        Double meanMinutesToAcknowledge,
        long correctiveActions,
        List<EquipmentRisk> equipment,
        String location,
        Map<String, Long> alertsBySeverity,
        Double meanMinutesToResolve,
        long escalations,
        Map<String, Long> correctiveActionsByType) {

    /** Share of the period one equipment item spent in each risk level, in percent. */
    public record EquipmentRisk(long equipmentId, String equipmentName, String locationName, double normalPct,
                                double warningPct, double criticalPct) {
    }
}

package com.team404.corrosion.report;

import com.team404.corrosion.report.ReportSummary.EquipmentRisk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The summary as CSV (RFC 4180): columns section, metric, value; one row per metric; CRLF line ends. A field with a
 * comma, quote or line break is quoted and its quotes doubled. Text that starts like a spreadsheet formula
 * (=, +, -, @) gets a leading apostrophe so opening the file in Excel never runs it.
 */
public final class ReportCsv {

    static final String HEADER = "section,metric,value";

    private ReportCsv() {
    }

    public static String write(ReportSummary r) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Period", "From", r.from() == null ? "start of records" : r.from()});
        rows.add(new String[] {"Period", "To (exclusive)", r.to() == null ? "now" : r.to()});
        rows.add(new String[] {"Period", "Location", r.location() == null ? "All locations" : r.location()});
        rows.add(new String[] {"Alerts", "Alerts opened", Long.toString(r.alertsOpened())});
        for (Map.Entry<String, Long> e : r.alertsBySeverity().entrySet()) {
            rows.add(new String[] {"Alerts", "Opened: " + e.getKey(), Long.toString(e.getValue())});
        }
        rows.add(new String[] {"Alerts", "Mean minutes to acknowledge", number(r.meanMinutesToAcknowledge())});
        rows.add(new String[] {"Alerts", "Mean minutes to resolve", number(r.meanMinutesToResolve())});
        rows.add(new String[] {"Alerts", "Escalations", Long.toString(r.escalations())});
        rows.add(new String[] {"Corrective actions", "Total", Long.toString(r.correctiveActions())});
        for (Map.Entry<String, Long> e : r.correctiveActionsByType().entrySet()) {
            rows.add(new String[] {"Corrective actions", e.getKey(), Long.toString(e.getValue())});
        }
        if (r.equipment().isEmpty()) {
            rows.add(new String[] {"Risk", "Time in each risk level", "not available yet"});
        }
        for (EquipmentRisk e : r.equipment()) {
            String name = e.locationName() == null ? e.equipmentName() : e.equipmentName() + " (" + e.locationName() + ")";
            rows.add(new String[] {"Risk", name + ": normal %", number(e.normalPct())});
            rows.add(new String[] {"Risk", name + ": warning %", number(e.warningPct())});
            rows.add(new String[] {"Risk", name + ": critical %", number(e.criticalPct())});
        }
        rows.add(new String[] {"Note", "Data", "Simulated sensor data (course project, not real equipment)"});

        var out = new StringBuilder(HEADER).append("\r\n");
        for (String[] row : rows) {
            out.append(field(row[0])).append(',').append(field(row[1])).append(',').append(field(row[2])).append("\r\n");
        }
        return out.toString();
    }

    /** One CSV field: formula-guarded, then quoted if it has to be. */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        String text = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 && !isNumber(value)
                ? "'" + value : value;
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0 || (!text.isEmpty() && (text.charAt(0) == ' ' || text.endsWith(" ")));
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    static String number(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static boolean isNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

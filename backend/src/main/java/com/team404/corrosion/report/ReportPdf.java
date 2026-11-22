package com.team404.corrosion.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.team404.corrosion.report.ReportSummary.EquipmentRisk;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** The summary as a one- or two-page PDF (OpenPDF): title, period, the same tables as the CSV, a simulated-data note. */
public final class ReportPdf {

    static final String TITLE = "Corrosion Watch report";
    static final String SIMULATED_NOTE = "Simulated data: the readings behind this report come from the Corrosion Watch "
            + "simulator (course project), not from real equipment.";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final Color HEADER_FILL = new Color(0xEE, 0xF2, 0xF8);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font NOTE = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.DARK_GRAY);

    private ReportPdf() {
    }

    public static byte[] write(ReportSummary r, Instant generatedAt) {
        var out = new ByteArrayOutputStream();
        var doc = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle(TITLE);
            doc.open();
            doc.add(new Paragraph(TITLE, TITLE_FONT));
            doc.add(new Paragraph("Period: " + period(r), BODY));
            doc.add(new Paragraph("Location: " + (r.location() == null ? "All locations" : r.location()), BODY));
            doc.add(new Paragraph("Generated: " + WHEN.format(generatedAt), BODY));
            doc.add(new Paragraph(SIMULATED_NOTE, NOTE));

            heading(doc, "Alerts");
            PdfPTable alerts = table(new float[] {3, 1}, "Metric", "Value");
            row(alerts, "Alerts opened", Long.toString(r.alertsOpened()));
            for (Map.Entry<String, Long> e : r.alertsBySeverity().entrySet()) {
                row(alerts, "  " + e.getKey(), Long.toString(e.getValue()));
            }
            row(alerts, "Mean time to acknowledge (min)", orDash(r.meanMinutesToAcknowledge()));
            row(alerts, "Mean time to resolve (min)", orDash(r.meanMinutesToResolve()));
            row(alerts, "Escalations to managers", Long.toString(r.escalations()));
            doc.add(alerts);

            heading(doc, "Corrective actions");
            PdfPTable actions = table(new float[] {3, 1}, "Type", "Count");
            for (Map.Entry<String, Long> e : r.correctiveActionsByType().entrySet()) {
                row(actions, e.getKey(), Long.toString(e.getValue()));
            }
            row(actions, "Total", Long.toString(r.correctiveActions()));
            doc.add(actions);

            heading(doc, "Time in each risk level");
            if (r.equipment().isEmpty()) {
                doc.add(new Paragraph("Not available yet: risk figures come from analytics.", BODY));
            } else {
                PdfPTable risk = table(new float[] {3, 3, 1, 1, 1}, "Equipment", "Location", "Normal %", "Warning %",
                        "Critical %");
                for (EquipmentRisk e : r.equipment()) {
                    row(risk, e.equipmentName(), e.locationName() == null ? "" : e.locationName(),
                            ReportCsv.number(e.normalPct()), ReportCsv.number(e.warningPct()),
                            ReportCsv.number(e.criticalPct()));
                }
                doc.add(risk);
            }
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build the PDF report", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    static String period(ReportSummary r) {
        String from = r.from() == null ? "start of records" : WHEN.format(Instant.parse(r.from()));
        String to = r.to() == null ? "now" : WHEN.format(Instant.parse(r.to()));
        return from + " to " + to;
    }

    private static String orDash(Double value) {
        return value == null ? "-" : ReportCsv.number(value);
    }

    private static void heading(Document doc, String text) throws DocumentException {
        var p = new Paragraph(text, HEADING);
        p.setSpacingBefore(16);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private static PdfPTable table(float[] widths, String... headers) {
        var table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : headers) {
            var cell = new PdfPCell(new Phrase(h, BOLD));
            cell.setBackgroundColor(HEADER_FILL);
            cell.setPadding(5);
            table.addCell(cell);
        }
        return table;
    }

    private static void row(PdfPTable table, String... values) {
        for (int i = 0; i < values.length; i++) {
            var cell = new PdfPCell(new Phrase(values[i], BODY));
            cell.setPadding(5);
            if (i > 0 && values.length == 2) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            table.addCell(cell);
        }
    }
}

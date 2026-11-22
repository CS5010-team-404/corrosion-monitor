package com.team404.corrosion.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.team404.corrosion.report.ReportSummary.EquipmentRisk;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CSV and PDF content for the same summary. */
class ReportFilesTest {

    private static final ReportSummary SUMMARY = summary(List.of(
            new EquipmentRisk(1, "Rack A1", "Server Room A", 90.04, 7.5, 2.46)));

    @Test
    void csvHasOneRowPerMetric() {
        String csv = ReportCsv.write(SUMMARY);
        List<String> lines = List.of(csv.split("\r\n"));

        assertThat(lines.get(0)).isEqualTo("section,metric,value");
        assertThat(csv).endsWith("\r\n");
        assertThat(lines).contains(
                "Period,From,2026-11-10T00:00:00Z",
                "Period,To (exclusive),2026-11-11T00:00:00Z",
                "Period,Location,Server Room A",
                "Alerts,Alerts opened,3",
                "Alerts,Opened: WARNING,2",
                "Alerts,Opened: CRITICAL,1",
                "Alerts,Mean minutes to acknowledge,6.0",
                "Alerts,Mean minutes to resolve,25.5",
                "Alerts,Escalations,1",
                "Corrective actions,Total,3",
                "Corrective actions,DEHUMIDIFIER_ON,2",
                "Risk,Rack A1 (Server Room A): normal %,90.0",
                "Risk,Rack A1 (Server Room A): critical %,2.5");
        assertThat(lines).anyMatch(l -> l.startsWith("Note,Data,\"Simulated sensor data"));
        assertThat(lines).allMatch(l -> columns(l) == 3);
    }

    @Test
    void csvQuotesCommasQuotesAndLineBreaksAndDefusesFormulas() {
        assertThat(ReportCsv.field("plain")).isEqualTo("plain");
        assertThat(ReportCsv.field("Room 1, east")).isEqualTo("\"Room 1, east\"");
        assertThat(ReportCsv.field("the \"cold\" rack")).isEqualTo("\"the \"\"cold\"\" rack\"");
        assertThat(ReportCsv.field("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(ReportCsv.field(" padded")).isEqualTo("\" padded\"");
        assertThat(ReportCsv.field("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ReportCsv.field("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ReportCsv.field("-1.5")).isEqualTo("-1.5");   // a number is left alone
        assertThat(ReportCsv.field(null)).isEmpty();
    }

    @Test
    void csvEscapesUserTypedActionTypesAndShowsMissingFigures() {
        var types = new LinkedHashMap<String, Long>();
        types.put("Fan, \"big\" one", 1L);
        var r = new ReportSummary(null, null, 0, null, 1, List.of(), null, Map.of("WARNING", 0L, "CRITICAL", 0L), null,
                0, types);

        String csv = ReportCsv.write(r);

        assertThat(csv).contains("Corrective actions,\"Fan, \"\"big\"\" one\",1\r\n")
                .contains("Period,From,start of records\r\n")
                .contains("Period,Location,All locations\r\n")
                .contains("Alerts,Mean minutes to acknowledge,\r\n")
                .contains("Risk,Time in each risk level,not available yet\r\n");
    }

    @Test
    void pdfHasTitlePeriodTablesAndTheSimulatedDataNote() throws Exception {
        byte[] pdf = ReportPdf.write(SUMMARY, Instant.parse("2026-11-21T09:30:00Z"));

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("Corrosion Watch report")
                .contains("Period: 2026-11-10 00:00 UTC to 2026-11-11 00:00 UTC")
                .contains("Location: Server Room A")
                .contains("Generated: 2026-11-21 09:30 UTC")
                .contains("Simulated data")
                .contains("Alerts opened").contains("Mean time to acknowledge (min)").contains("6.0")
                .contains("Mean time to resolve (min)").contains("25.5")
                .contains("Escalations to managers")
                .contains("DEHUMIDIFIER_ON")
                .contains("Rack A1").contains("90.0");
    }

    @Test
    void pdfSaysWhenRiskFiguresAreNotAvailable() throws Exception {
        String text = text(ReportPdf.write(summary(List.of()), Instant.EPOCH));
        assertThat(text).contains("Not available yet");
    }

    private static String text(byte[] pdf) throws Exception {
        var reader = new PdfReader(pdf);
        try {
            var extractor = new PdfTextExtractor(reader);
            var all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append('\n');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }

    private static ReportSummary summary(List<EquipmentRisk> equipment) {
        var severity = new LinkedHashMap<String, Long>();
        severity.put("WARNING", 2L);
        severity.put("CRITICAL", 1L);
        var types = new LinkedHashMap<String, Long>();
        types.put("DEHUMIDIFIER_ON", 2L);
        types.put("RESOLVED", 1L);
        return new ReportSummary("2026-11-10T00:00:00Z", "2026-11-11T00:00:00Z", 3, 6.0, 3, equipment,
                "Server Room A", severity, 25.5, 1, types);
    }

    /** Counts fields in one CSV line, honouring quotes. */
    private static int columns(String line) {
        int n = 1;
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                n++;
            }
        }
        return n;
    }
}

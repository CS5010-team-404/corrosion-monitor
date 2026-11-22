package edu.neu.ecp.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole evaluation end to end, in quick mode, on a small SYNTHETIC file with the real file's columns (never a
 * slice of the real dataset): rooms with a daily temperature cycle, and a "bathroom" (location 5) whose humidity
 * peaks twice a day so that risk events exist. Checks that every table and file is produced and that a second run
 * gives byte-identical metrics.
 */
class RealDataEvaluationTest {

    static Path fixture(Path dir) throws Exception {
        DateTimeFormatter ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<String> lines = new ArrayList<>();
        StringBuilder h = new StringBuilder("\"date\",\"Appliances\",\"lights\"");
        for (int n = 1; n <= 9; n++) {
            h.append(",\"T").append(n).append("\",\"RH_").append(n).append('"');
        }
        lines.add(h.append(",\"T_out\"").toString());
        LocalDateTime t0 = LocalDateTime.of(2016, 1, 11, 0, 0);
        int rows = 6 * 24 * 4; // four days
        for (int i = 0; i < rows; i++) {
            double day = 2 * Math.PI * i / 144.0;
            StringBuilder r = new StringBuilder("\"").append(t0.plusMinutes(10L * i).format(ts)).append("\",\"60\",\"0\"");
            for (int n = 1; n <= 9; n++) {
                double temp = 19 + n * 0.3 + 1.5 * Math.sin(day + n);
                double rh = 42 + 4 * Math.cos(day + n);
                if (n == 5) {
                    double shower = Math.max(0, Math.cos(2 * day)) ;
                    rh = 50 + 45 * Math.pow(shower, 8); // two humid peaks a day, close to saturation
                }
                r.append(String.format(Locale.ROOT, ",\"%.3f\",\"%.3f\"", temp, rh));
            }
            lines.add(r.append(",\"6.6\"").toString());
        }
        Path csv = dir.resolve("synthetic.csv");
        Files.write(csv, lines);
        return csv;
    }

    @Test
    void runsEveryExperimentAndIsDeterministic(@TempDir Path dir) throws Exception {
        Path csv = fixture(dir);
        Path out1 = dir.resolve("out1");
        Path out2 = dir.resolve("out2");
        RealDataEvaluation.main(new String[] {"--csv", csv.toString(), "--out", out1.toString(), "--models",
            dir.resolve("m1").toString(), "--quick"});
        String md = Files.readString(out1.resolve("real-data-results.md"));
        for (String section : List.of("## Cleaning", "## 1. Leave-one-location-out", "### Per held-out location",
                "## 2. Chronological split", "## 3. Surface-offset sensitivity", "## 4. Final candidates",
                "## 5. Acceptance rule", "### GBT sim+real:", "QUICK MODE")) {
            assertTrue(md.contains(section), "missing " + section);
        }
        assertTrue(Files.size(dir.resolve("m1").resolve("candidate-GBT-real.json")) > 0);
        assertTrue(Files.readString(out1.resolve("chosen-hyperparameters.csv")).contains("lolo-1.5-loc5"));
        String metrics = Files.readString(out1.resolve("real-data-metrics.csv"));
        assertTrue(metrics.contains("lolo,1.5,location5,production (sim ridge)"));
        assertTrue(metrics.contains("sim-test,,all,GBT sim+real"));
        RealDataEvaluation.main(new String[] {"--csv", csv.toString(), "--out", out2.toString(), "--models",
            dir.resolve("m2").toString(), "--quick"});
        assertEquals(metrics, Files.readString(out2.resolve("real-data-metrics.csv")));
        assertEquals(Files.readString(out1.resolve("chosen-hyperparameters.csv")),
                Files.readString(out2.resolve("chosen-hyperparameters.csv")));
    }
}

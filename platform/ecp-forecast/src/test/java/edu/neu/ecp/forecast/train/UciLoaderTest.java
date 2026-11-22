package edu.neu.ecp.forecast.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.forecast.model.MarginPoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic fixture with the real file's columns and quoting; never a slice of the real dataset. */
class UciLoaderTest {

    static final String HEADER = "\"date\",\"Appliances\",\"lights\",\"T1\",\"RH_1\",\"T2\",\"RH_2\",\"T3\",\"RH_3\",\"T4\","
            + "\"RH_4\",\"T5\",\"RH_5\",\"T6\",\"RH_6\",\"T7\",\"RH_7\",\"T8\",\"RH_8\",\"T9\",\"RH_9\",\"T_out\","
            + "\"Press_mm_hg\",\"RH_out\",\"Windspeed\",\"Visibility\",\"Tdewpoint\",\"rv1\",\"rv2\"";
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A row where location n has temperature 20 + n + minute/100 and humidity 40 + n. */
    static String row(LocalDateTime at, double bump) {
        StringBuilder sb = new StringBuilder("\"").append(at.format(TS)).append("\",\"  60\",\"0\"");
        for (int n = 1; n <= 9; n++) {
            sb.append(String.format(Locale.ROOT, ",\"%.3f\",\"%.3f\"", 20 + n + bump, 40.0 + n));
        }
        sb.append(",\" 6.6\",\"733.5\",\" 92\",\" 7\",\"63\",\" 5.3\",\"13.27\",\"13.27\"");
        return sb.toString();
    }

    static List<String> regular(int n) {
        List<String> lines = new ArrayList<>(List.of(HEADER));
        LocalDateTime t = LocalDateTime.of(2016, 1, 11, 17, 0);
        for (int i = 0; i < n; i++) {
            lines.add(row(t.plusMinutes(10L * i), i / 100.0));
        }
        return lines;
    }

    @Test
    void readsAllNineLocationsFromQuotedColumns(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("fixture.csv");
        Files.write(f, regular(12));
        Map<Integer, UciLoader.Location> d = UciLoader.load(f);
        assertEquals(9, d.size());
        UciLoader.Location l3 = d.get(3);
        assertEquals(1, l3.segments().size());
        assertEquals(12, l3.segments().get(0).size());
        assertEquals(23.0, l3.segments().get(0).get(0).tempC(), 1e-9);
        assertEquals(43.0, l3.segments().get(0).get(0).humidityPct(), 1e-9);
        assertEquals(Instant.parse("2016-01-11T17:00:00Z"), l3.segments().get(0).get(0).at());
        assertTrue(d.get(6).outdoor());
        assertEquals(new UciLoader.Cleaning(12, 0, 0, 0, 1, 0, 0, 12), l3.cleaning());
    }

    @Test
    void dropsBadRowsAndCountsEachRule() {
        List<String> lines = regular(30);
        lines.set(3, lines.get(3).replace("\"2016-01-11 17:20:00\"", "\"not a date\""));      // unparsable
        lines.add(5, lines.get(5));                                                            // duplicate time
        lines.set(20, lines.get(20).replaceFirst(",\"21\\.[0-9]+\",", ",\"85.0\","));           // T1 impossible
        Map<Integer, UciLoader.Location> d = UciLoader.parse(lines);
        UciLoader.Cleaning c1 = d.get(1).cleaning();
        assertEquals(31, c1.rows());
        assertEquals(1, c1.unparsable());
        assertEquals(1, c1.duplicates());
        assertEquals(1, c1.impossible());
        // location 2 only loses the unparsable timestamp and the duplicate; the lost 17:20 reading leaves a gap,
        // so 17:00 and 17:10 form a segment too short to use
        UciLoader.Cleaning c2 = d.get(2).cleaning();
        assertEquals(0, c2.impossible());
        assertEquals(2, c2.segments());
        assertEquals(1, c2.shortSegments());
        assertEquals(27, c2.kept());
    }

    @Test
    void cutsAtGapsAndNeverFills() {
        List<String> lines = regular(40);
        lines.remove(16); // one missing reading: 15 readings, a 20-minute gap, 24 readings
        Map<Integer, UciLoader.Location> d = UciLoader.parse(lines);
        UciLoader.Location l = d.get(1);
        assertEquals(2, l.segments().size());
        assertEquals(15, l.segments().get(0).size());
        assertEquals(24, l.segments().get(1).size());
        for (List<UciLoader.Reading> s : l.segments()) {
            for (int i = 1; i < s.size(); i++) {
                assertEquals(600, s.get(i).at().getEpochSecond() - s.get(i - 1).at().getEpochSecond());
            }
        }
    }

    @Test
    void dropsSegmentsTooShortForHistoryAndFuture() {
        List<String> lines = regular(30);
        lines.remove(6); // segment of 5 readings, then 24
        UciLoader.Location l = UciLoader.parse(lines).get(4);
        assertEquals(2, l.cleaning().segments());
        assertEquals(1, l.cleaning().shortSegments());
        assertEquals(5, l.cleaning().readingsInShortSegments());
        assertEquals(1, l.segments().size());
        assertEquals(24, l.cleaning().kept());
    }

    @Test
    void rejectsAFileWithoutTheColumns() {
        assertThrows(IllegalArgumentException.class, () -> UciLoader.parse(List.of("\"date\",\"T1\"", "\"2016-01-11 17:00:00\",\"20\"")));
        assertThrows(IllegalArgumentException.class, () -> UciLoader.parse(List.of()));
    }

    @Test
    void perMinuteInterpolatesOnlyBetweenNeighboursAndKeepsReadingsExact() {
        Instant t = Instant.parse("2016-01-11T17:00:00Z");
        List<UciLoader.Reading> seg = List.of(new UciLoader.Reading(t, 20, 50),
                new UciLoader.Reading(t.plusSeconds(600), 21, 60), new UciLoader.Reading(t.plusSeconds(1200), 19, 40));
        List<MarginPoint> pm = UciLoader.perMinute(seg, 1.5);
        assertEquals(21, pm.size());
        assertEquals(20.0, pm.get(0).tempC(), 1e-12);
        assertEquals(21.0, pm.get(10).tempC(), 1e-12);
        assertEquals(60.0, pm.get(10).humidityPct(), 1e-12);
        assertEquals(19.0, pm.get(20).tempC(), 1e-12);
        assertEquals(20.5, pm.get(5).tempC(), 1e-12);
        assertEquals(55.0, pm.get(5).humidityPct(), 1e-12);
        assertEquals(20.5 - 1.5, pm.get(5).surfaceC(), 1e-12);
        assertEquals(t.plusSeconds(60 * 13), pm.get(13).at());
    }
}

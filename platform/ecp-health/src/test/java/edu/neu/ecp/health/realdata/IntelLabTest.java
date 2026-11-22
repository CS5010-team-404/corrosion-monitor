package edu.neu.ecp.health.realdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.health.domain.HealthIssue;
import edu.neu.ecp.health.domain.HealthPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic fixture in the Intel Lab format; never a slice of the real file. */
class IntelLabTest {

    static final LocalDateTime T0 = LocalDateTime.of(2004, 2, 28, 0, 0, 0);
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    static String line(int epoch, int mote, double t, double rh, double v) {
        return String.format(Locale.ROOT, "%s %d %d %.4f %.4f 45.08 %.5f",
                T0.plusSeconds(31L * epoch).format(TS), epoch, mote, t, rh, v);
    }

    @Test
    void parsesCountsAndSortsWithoutFixingAnything(@TempDir Path dir) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(line(2, 1, 20.5, 40, 2.6));
        lines.add(line(1, 1, 20.4, 40, 2.6));          // out of order in the file: sorted on load
        lines.add(line(1, 1, 99.0, 40, 2.6));          // repeated (mote, epoch): dropped, first kept
        lines.add("2004-02-28 00:00:00.1 3 2 19.0");   // missing fields
        lines.add("2004-02-28 xx:00:00.1 3 2 19.0 40 45 2.6"); // unparsable
        lines.add(line(3, 77, 20, 40, 2.6));           // mote not in mote_locs
        lines.add(line(3, 2, 122.153, -3.9, 2.03));    // a dying-battery reading stays as it is
        lines.add("2004-02-28 00:01:33 4 2 19.0 40 45 2.6"); // no fractional seconds
        Path data = dir.resolve("data.txt.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(data))) {
            out.write(String.join("\n", lines).getBytes());
        }
        Path locs = dir.resolve("mote_locs.txt");
        Files.writeString(locs, "1 21.5 23\n2 24.5 20\n");
        IntelLabLoader.Data d = IntelLabLoader.load(data, locs);
        assertEquals(new IntelLabLoader.Counts(8, 2, 1, 1, 4), d.counts());
        List<IntelLabLoader.Reading> m1 = d.motes().get(1);
        assertEquals(List.of(1L, 2L), m1.stream().map(IntelLabLoader.Reading::epoch).toList());
        assertEquals(20.4, m1.get(0).temperatureC(), 1e-9);
        IntelLabLoader.Reading dying = d.motes().get(2).get(0);
        assertEquals(122.153, dying.temperatureC(), 1e-9);
        assertEquals(2.03, dying.voltage(), 1e-9);
        assertEquals(T0.toInstant(ZoneOffset.UTC).plusSeconds(93), dying.at());
    }

    static List<IntelLabLoader.Reading> series(int n, java.util.function.IntFunction<double[]> tRhV) {
        List<IntelLabLoader.Reading> out = new ArrayList<>();
        Instant t0 = T0.toInstant(ZoneOffset.UTC);
        for (int i = 0; i < n; i++) {
            double[] x = tRhV.apply(i);
            out.add(new IntelLabLoader.Reading(t0.plusSeconds(31L * i), i, x[0], x[1], 100, x[2]));
        }
        return out;
    }

    @Test
    void referenceLabelsFollowTheStatedRules() {
        // 0-149: normal and varying; 150-299: frozen for 77 minutes; 300-309: 122 °C at 2.1 V
        List<IntelLabLoader.Reading> rs = series(310, i -> i < 150 ? new double[] {20 + i * 0.01, 40, 2.7}
                : i < 300 ? new double[] {21.5, 41, 2.6} : new double[] {122.153, -3.9, 2.1});
        ReferenceLabels.Labels l = ReferenceLabels.of(rs);
        assertFalse(l.faulty(10));
        assertTrue(l.flat()[200]);
        assertFalse(l.implausible()[200]);
        assertTrue(l.implausible()[305] && l.lowBattery()[305]);
        // a short flat run (under an hour) is not "flat"
        List<IntelLabLoader.Reading> shortRun = series(60, i -> new double[] {i < 30 ? 20 + i * 0.01 : 21, 40, 2.7});
        assertFalse(ReferenceLabels.of(shortRun).flat()[45]);
    }

    @Test
    void replayFlagsAFrozenSensorAndADeadBatteryAndScoresThem() {
        List<IntelLabLoader.Reading> rs = series(400, i -> i < 150 ? new double[] {20 + (i % 7) * 0.05, 40 + (i % 5) * 0.1, 2.7}
                : i < 300 ? new double[] {21.5, 41, 2.6} : new double[] {122.153, -3.9, 2.1});
        Instant end = rs.get(rs.size() - 1).at().plus(Duration.ofMinutes(10));
        HealthReplay.Result r = HealthReplay.run(1, rs, HealthPolicy.defaults(), Duration.ofSeconds(31),
                Duration.ofSeconds(5), end);
        Set<HealthIssue> seen = new java.util.HashSet<>();
        r.episodes().forEach(e -> seen.add(e.issue()));
        assertTrue(seen.contains(HealthIssue.STUCK), "frozen run should be STUCK: " + seen);
        assertTrue(seen.contains(HealthIssue.OUT_OF_RANGE), "humidity -3.9 % is impossible: " + seen);
        assertTrue(seen.contains(HealthIssue.OFFLINE), "silence after the last reading: " + seen);
        HealthReplay.Confusion stuckVsFlat = r.confusion().get(new HealthReplay.Cell("STUCK", HealthReplay.Reference.FLAT));
        assertTrue(stuckVsFlat.tp > 0);
        assertTrue(stuckVsFlat.recall() > 0.5);
        assertEquals(r.ticks(), stuckVsFlat.tp + stuckVsFlat.fp + stuckVsFlat.fn + stuckVsFlat.tn);
        // the replay is deterministic
        HealthReplay.Result again = HealthReplay.run(1, rs, HealthPolicy.defaults(), Duration.ofSeconds(31),
                Duration.ofSeconds(5), end);
        assertEquals(r.episodes(), again.episodes());
    }

    @Test
    void robustZFlagsAJumpButNotSlowDrift() {
        List<IntelLabLoader.Reading> rs = series(200, i -> new double[] {i == 150 ? 60 : 20 + i * 0.002, 40, 2.7});
        boolean[] f = RobustZ.flags(rs);
        assertTrue(f[150]);
        assertFalse(f[149]);
        assertFalse(f[120]);
        assertFalse(f[10]); // not enough history yet
    }

    @Test
    void indoorRangeVariantOnlyChangesTheTemperatureLimits() {
        HealthPolicy d = HealthPolicy.defaults();
        HealthPolicy v = IntelLabEvaluation.withTempRange(d, 0, 60);
        assertEquals(60, v.tempMaxC());
        assertEquals(0, v.tempMinC());
        assertEquals(d.stuckMinReadings(), v.stuckMinReadings());
        assertEquals(d.window(), v.window());
        assertEquals(d.humidityMaxPct(), v.humidityMaxPct());
    }
}

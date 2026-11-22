package edu.neu.ecp.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.forecast.model.Features;
import edu.neu.ecp.forecast.model.MarginPoint;
import edu.neu.ecp.forecast.train.UciLoader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ExamplesTest {

    static final Instant T0 = Instant.parse("2016-02-01T00:00:00Z");

    static List<UciLoader.Reading> randomSegment(long seed, int n) {
        Random r = new Random(seed);
        List<UciLoader.Reading> s = new ArrayList<>();
        double t = 20;
        double rh = 50;
        for (int i = 0; i < n; i++) {
            t += r.nextGaussian() * 0.3;
            rh = Math.max(5, Math.min(99, rh + r.nextGaussian() * 2));
            s.add(new UciLoader.Reading(T0.plusSeconds(600L * i), t, rh));
        }
        return s;
    }

    /**
     * Leakage test: for every forecast moment, replacing every reading after it with different values changes
     * neither the features nor the trend baseline. Only the targets may change.
     */
    @Test
    void featuresNeverUseFutureReadings() {
        List<UciLoader.Reading> seg = randomSegment(7, 30);
        List<Example> base = Examples.of(1, 0, UciLoader.perMinute(seg, 1.5), 10, 10);
        for (int cut = 3; cut < seg.size(); cut++) {
            List<UciLoader.Reading> changed = new ArrayList<>(seg.subList(0, cut + 1));
            Random r = new Random(cut);
            for (int i = cut + 1; i < seg.size(); i++) {
                changed.add(new UciLoader.Reading(seg.get(i).at(), 5 + r.nextDouble() * 30, 10 + r.nextDouble() * 85));
            }
            List<Example> other = Examples.of(1, 0, UciLoader.perMinute(changed, 1.5), 10, 10);
            Instant origin = seg.get(cut).at();
            Example a = base.stream().filter(e -> e.at().equals(origin)).findFirst().orElseThrow();
            Example b = other.stream().filter(e -> e.at().equals(origin)).findFirst().orElseThrow();
            assertArrayEquals(a.x(), b.x(), 0.0, "features at reading " + cut + " changed with the future");
            assertEquals(a.trendSlope(), b.trendSlope(), 0.0);
            assertEquals(a.marginNow(), b.marginNow(), 0.0);
        }
    }

    @Test
    void examplesSitOnRealReadingsOnlyWithThirtyMinutesOfHistory() {
        List<UciLoader.Reading> seg = randomSegment(3, 20);
        List<MarginPoint> pm = UciLoader.perMinute(seg, 1.5);
        List<Example> ex = Examples.of(2, 5, pm, 10, 10);
        assertEquals(seg.get(3).at(), ex.get(0).at()); // 30 minutes of history: the fourth reading
        assertEquals(17, ex.size());
        for (Example e : ex) {
            assertEquals(0, (e.at().getEpochSecond() - T0.getEpochSecond()) % 600);
            assertEquals(Features.NAMES.size(), e.x().length);
            assertEquals(2, e.location());
            assertEquals(5, e.series());
        }
        // the last 5 readings have no full 60-minute future: replay only
        assertEquals(11, ex.stream().filter(Example::scored).count());
        assertNull(ex.get(ex.size() - 1).future());
    }

    @Test
    void targetsAreRealReadingsAt30And60AndInterpolatedAt15() {
        List<UciLoader.Reading> seg = randomSegment(11, 12);
        List<MarginPoint> pm = UciLoader.perMinute(seg, 1.5);
        Example e = Examples.of(1, 0, pm, 10, 10).get(0); // at reading 3
        MarginPoint r6 = pm.get(60);
        MarginPoint r9 = pm.get(90);
        assertEquals(r6.marginC(), e.future()[1], 1e-12);
        assertEquals(r9.marginC(), e.future()[2], 1e-12);
        assertEquals(pm.get(45).marginC(), e.future()[0], 1e-12); // between readings 4 and 5
    }

    @Test
    void riskLabelLooksAtEveryReadingWithinTheHorizon() {
        // margin = surface − dew point; humidity 100% at air 20 °C, offset 0 gives margin 0
        List<UciLoader.Reading> seg = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double rh = i == 8 ? 99.9 : 40; // one humid reading 50 minutes after the first example
            seg.add(new UciLoader.Reading(T0.plusSeconds(600L * i), 20, rh));
        }
        List<Example> ex = Examples.of(1, 0, UciLoader.perMinute(seg, 0.0), 10, 10);
        Example first = ex.get(0); // reading 3: reading 8 is +50 min
        assertFalse(first.within30());
        assertTrue(first.within60());
        Example second = ex.get(2); // reading 5: reading 8 is +30 min
        assertTrue(second.within30());
        assertTrue(second.within60());
        assertTrue(first.marginNow() > Examples.CRITICAL_C);
    }

    @Test
    void strideMustBeAMultipleOfStep() {
        assertThrows(IllegalArgumentException.class, () -> Examples.of(1, 0, List.of(), 10, 15));
    }
}

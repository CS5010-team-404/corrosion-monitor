package edu.neu.ecp.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsAndAcceptanceTest {

    static final Instant T0 = Instant.parse("2016-02-01T00:00:00Z");

    /** A hand-made series every 10 minutes with the given margins; futures from the same list. */
    static List<Example> series(double... margins) {
        List<Example> out = new ArrayList<>();
        for (int i = 0; i < margins.length; i++) {
            double[] future = i + 6 < margins.length
                    ? new double[] {(margins[i + 1] + margins[i + 2]) / 2, margins[i + 3], margins[i + 6]} : null;
            boolean w30 = false;
            boolean w60 = false;
            if (future != null && margins[i] > 1) {
                for (int k = 1; k <= 6; k++) {
                    if (margins[i + k] <= 1) {
                        w60 = true;
                        w30 |= k <= 3;
                    }
                }
            }
            out.add(new Example(1, 0, T0.plusSeconds(600L * i), new double[] {margins[i]}, margins[i], future, w30,
                    w60, 0));
        }
        return out;
    }

    /** A predictor that knows the future exactly, and warns from it. */
    static Predictor oracle() {
        return new Predictor() {
            public String name() {
                return "oracle";
            }

            public double[] margins(Example e) {
                return e.future();
            }

            public boolean warn(Example e, int h) {
                return e.scored() && e.within(h);
            }
        };
    }

    @Test
    void perfectPredictorHasZeroErrorAndPerfectWarnings() {
        List<Example> s = series(5, 5, 4, 3, 2.5, 2, 1.5, 0.5, 0.5, 2, 4, 5, 5, 5, 5, 5, 5);
        Metrics.Result r = Metrics.aggregate(List.of(Metrics.score(oracle(), s, 10)));
        assertEquals(0.0, r.mae()[2], 1e-12);
        assertEquals(1.0, r.f1()[1], 1e-12);
        assertEquals(1.0, r.precision()[0], 1e-12);
        assertEquals(11, r.points());
    }

    @Test
    void persistenceErrorIsTheChangeInMargin() {
        List<Example> s = series(5, 5, 5, 5, 4, 4, 4, 4, 4, 4);
        Metrics.Result r = Metrics.aggregate(List.of(Metrics.score(Baselines.persistence(), s, 10)));
        // points 0..3 are scored; at +60 the margin is 4 for all of them
        assertEquals(1.0, r.mae()[2], 1e-12);
        assertEquals(1.0, r.rmse()[2], 1e-12);
        assertEquals(0.0, r.f1()[1], 1e-12);
    }

    @Test
    void replayCountsLeadTimeAndFalseAlarms() {
        // margin falls through 1 °C at index 9; the rule warns from index 5 (margin <= 3) to 8
        double[] m = {5, 5, 5, 5, 4, 3, 2.5, 2, 1.5, 0.5, 0.5, 0.5, 4, 4, 4, 2.5, 4, 4, 4, 4, 4, 4, 4, 4, 4};
        List<Example> s = series(m);
        Metrics.Scored sc = Metrics.score(Baselines.rule(), s, 10);
        Metrics.Replay rep = Metrics.replay(sc);
        assertEquals(List.of(40.0), rep.leads()); // warnings at 5, 6, 7, 8 = 40 minutes before the crossing
        assertEquals(1, rep.falseEpisodes()); // the lone 2.5 at index 15 is never followed by a crossing
        Metrics.Result r = Metrics.aggregate(List.of(sc));
        assertEquals(1, r.crossings());
        assertEquals(40.0, r.medianLeadMin(), 1e-12);
        assertEquals(1 / (25 * 10 / 1440.0), r.falseAlarmsPerDay(), 1e-9);
    }

    @Test
    void replayBridgesASingleQuietReading() {
        double[] m = {5, 5, 5, 5, 5, 5, 3, 4, 3, 2, 0.5, 0.5};
        Metrics.Scored sc = Metrics.score(Baselines.rule(), series(m), 10);
        assertEquals(List.of(40.0), Metrics.replay(sc).leads()); // warnings at 6, 8, 9 with 7 bridged
    }

    @Test
    void medianOfEvenCountAveragesTheMiddle() {
        assertEquals(2.5, Metrics.median(List.of(1.0, 2.0, 3.0, 4.0)), 1e-12);
        assertTrue(Double.isNaN(Metrics.median(List.of())));
    }

    // ------------------------------------------------------------------------------------------- acceptance

    static Metrics.Result result(double[] mae, double[] f1, double falseAlarms) {
        return new Metrics.Result(100, mae, mae, 10, mae, mae, f1, f1, f1, new int[] {5, 5}, 3, 30, 1, falseAlarms, 10);
    }

    static Acceptance.Evidence ev(double mae, double f1, double fa) {
        Metrics.Result r = result(new double[] {mae, mae, mae}, new double[] {f1, f1}, fa);
        return new Acceptance.Evidence(r, r, r);
    }

    @Test
    void acceptsOnlyWhenEveryCheckPasses() {
        Acceptance.Evidence prod = ev(0.5, 0.6, 2.0);
        assertTrue(Acceptance.decide(ev(0.4, 0.6, 2.0), prod, 0.1, 50_000).accepted());
        // equal MAE on real data is not "lower"
        assertFalse(Acceptance.decide(ev(0.5, 0.6, 2.0), prod, 0.1, 50_000).accepted());
        // F1 worse beyond the tolerance
        assertFalse(Acceptance.decide(ev(0.4, 0.59, 2.0), prod, 0.1, 50_000).accepted());
        // F1 within the tolerance is fine
        assertTrue(Acceptance.decide(ev(0.4, 0.596, 2.0), prod, 0.1, 50_000).accepted());
        // more false alarms
        assertFalse(Acceptance.decide(ev(0.4, 0.6, 2.1), prod, 0.1, 50_000).accepted());
        // too slow, too big
        assertFalse(Acceptance.decide(ev(0.4, 0.6, 2.0), prod, 6.0, 50_000).accepted());
        Acceptance.Decision big = Acceptance.decide(ev(0.4, 0.6, 2.0), prod, 0.1, 2_000_000);
        assertFalse(big.accepted());
        assertEquals(List.of("d2"), big.failed().stream().map(Acceptance.Check::id).toList());
    }

    @Test
    void simulatedMaeMayNotGetWorseButRealMaeMustImprove() {
        Metrics.Result realBetter = result(new double[] {0.4, 0.4, 0.4}, new double[] {0.6, 0.6}, 2.0);
        Metrics.Result simWorse = result(new double[] {0.52, 0.5, 0.5}, new double[] {0.6, 0.6}, 2.0);
        Acceptance.Evidence cand = new Acceptance.Evidence(realBetter, realBetter, simWorse);
        Acceptance.Decision d = Acceptance.decide(cand, ev(0.5, 0.6, 2.0), 0.1, 1000);
        assertEquals(List.of("b"), d.failed().stream().map(Acceptance.Check::id).toList());
    }
}

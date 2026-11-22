package edu.neu.ecp.forecast.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GbtTest {

    record Data(List<double[]> x, double[] y) {
    }

    /** y = sin(x0) + 0.5 x1 (+ noise); x2 is pure noise. */
    static Data data(long seed, int n, double noise) {
        Random r = new Random(seed);
        List<double[]> x = new ArrayList<>();
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * 6 - 3;
            double b = r.nextDouble() * 4 - 2;
            x.add(new double[] {a, b, r.nextGaussian()});
            y[i] = Math.sin(a) + 0.5 * b + noise * r.nextGaussian();
        }
        return new Data(x, y);
    }

    static double mae(Gbt.Model m, Data d) {
        double s = 0;
        for (int i = 0; i < d.y().length; i++) {
            s += Math.abs(m.predict(d.x().get(i)) - d.y()[i]);
        }
        return s / d.y().length;
    }

    @Test
    void fitsAKnownNonLinearFunction() {
        Data train = data(1, 4000, 0.05);
        Data test = data(2, 1000, 0.0);
        Gbt.Model m = Gbt.fit(train.x(), train.y(), null, Gbt.Params.of(Gbt.Loss.SQUARED, 4, 10).withTrees(400));
        assertTrue(mae(m, test) < 0.08, "MAE " + mae(m, test));
        // a linear model cannot do this: the best line through sin(x) on [-3, 3] leaves MAE well above 0.2
    }

    @Test
    void respectsMaxDepthAndMinLeafSize() {
        Data d = data(3, 1500, 0.1);
        for (int depth : new int[] {1, 2, 4}) {
            Gbt.Model m = Gbt.fit(d.x(), d.y(), null, Gbt.Params.of(Gbt.Loss.SQUARED, depth, 25).withTrees(30));
            for (Gbt.Tree t : m.trees()) {
                assertTrue(t.depth() <= depth);
                assertTrue(t.nodes() <= (1 << (depth + 1)) - 1);
                for (int count : leafCounts(t, d.x())) {
                    assertTrue(count >= 25, "leaf with " + count + " rows");
                }
            }
        }
    }

    static List<Integer> leafCounts(Gbt.Tree t, List<double[]> x) {
        int[] counts = new int[t.nodes()];
        for (double[] row : x) {
            int n = 0;
            while (t.feature()[n] >= 0) {
                n = row[t.feature()[n]] <= t.threshold()[n] ? t.left()[n] : t.right()[n];
            }
            counts[n]++;
        }
        List<Integer> out = new ArrayList<>();
        for (int n = 0; n < counts.length; n++) {
            if (t.feature()[n] < 0) {
                out.add(counts[n]);
            }
        }
        return out;
    }

    @Test
    void isDeterministic() {
        Data d = data(4, 1200, 0.2);
        Gbt.Params p = Gbt.Params.of(Gbt.Loss.HUBER, 3, 20).withTrees(50).withHuberDelta(0.3);
        Gbt.Model a = Gbt.fit(d.x(), d.y(), null, p);
        Gbt.Model b = Gbt.fit(d.x(), d.y(), null, p);
        for (double[] row : d.x()) {
            assertEquals(a.predict(row), b.predict(row), 0.0);
        }
        for (int i = 0; i < a.trees().size(); i++) {
            assertArrayEquals(a.trees().get(i).feature(), b.trees().get(i).feature());
        }
    }

    @Test
    void earlyStoppingKeepsTheBestValidationRound() {
        Data train = data(5, 300, 0.8); // small and noisy: overfits quickly
        Data val = data(6, 300, 0.8);
        Gbt.Params p = new Gbt.Params(Gbt.Loss.SQUARED, 5, 2, 0.3, 400, 10, 1.0, 64, 0.0);
        Gbt.Fit f = Gbt.fit(train.x(), train.y(), null, val.x(), val.y(), p);
        assertTrue(f.bestTrees() < 400, "should stop early");
        assertEquals(f.bestTrees(), f.model().trees().size());
        double best = f.validationCurve()[f.bestTrees()];
        for (double v : f.validationCurve()) {
            assertTrue(best <= v + 1e-12);
        }
        // stopped `patience` rounds after the best
        assertEquals(f.bestTrees() + 10, f.validationCurve().length - 1);
    }

    @Test
    void huberIsRobustToOutliers() {
        Data d = data(7, 2000, 0.05);
        double[] dirty = d.y().clone();
        for (int i = 0; i < dirty.length; i += 20) {
            dirty[i] += 40; // 5% wild outliers
        }
        Data clean = data(8, 800, 0.0);
        Gbt.Params base = Gbt.Params.of(Gbt.Loss.SQUARED, 3, 20).withTrees(200);
        double sq = mae(Gbt.fit(d.x(), dirty, null, base), clean);
        double hub = mae(Gbt.fit(d.x(), dirty, null, Gbt.Params.of(Gbt.Loss.HUBER, 3, 20).withTrees(200).withHuberDelta(0.2)), clean);
        assertTrue(hub < sq / 3, "huber " + hub + " squared " + sq);
    }

    @Test
    void logisticLossGivesCalibratedLookingProbabilitiesAndUsesWeights() {
        Random r = new Random(9);
        List<double[]> x = new ArrayList<>();
        double[] y = new double[3000];
        for (int i = 0; i < y.length; i++) {
            double a = r.nextDouble() * 4 - 2;
            x.add(new double[] {a});
            y[i] = r.nextDouble() < 1 / (1 + Math.exp(-3 * a)) ? 1 : 0;
        }
        Gbt.Model m = Gbt.fit(x, y, null, Gbt.Params.of(Gbt.Loss.LOGISTIC, 2, 50).withTrees(150));
        assertTrue(m.predict(new double[] {-1.5}) < 0.1);
        assertTrue(m.predict(new double[] {1.5}) > 0.9);
        assertEquals(0.5, m.predict(new double[] {0.0}), 0.15);
        double[] w = new double[y.length];
        for (int i = 0; i < w.length; i++) {
            w[i] = y[i] == 1 ? 3 : 1;
        }
        Gbt.Model weighted = Gbt.fit(x, y, w, Gbt.Params.of(Gbt.Loss.LOGISTIC, 2, 50).withTrees(150));
        assertTrue(weighted.predict(new double[] {0.0}) > m.predict(new double[] {0.0}));
        assertThrows(IllegalArgumentException.class, () -> Gbt.fit(x, new double[3000 - 1], null,
                Gbt.Params.of(Gbt.Loss.SQUARED, 2, 5)));
    }

    @Test
    void binsSplitExactlyAtStoredThresholds() {
        double[] edges = Gbt.cuts(List.of(new double[] {1}, new double[] {2}, new double[] {3}, new double[] {4}), 0, 4);
        for (int i = 1; i < edges.length; i++) {
            assertTrue(edges[i] > edges[i - 1]);
        }
        assertEquals(0, Gbt.bin(edges, edges[0]));
        assertEquals(edges.length, Gbt.bin(edges, 1e9));
        assertThrows(IllegalArgumentException.class, () -> Gbt.cuts(List.<double[]>of(new double[] {Double.NaN}), 0, 4));
        assertThrows(IllegalArgumentException.class, () -> Gbt.Params.of(Gbt.Loss.SQUARED, 0, 5));
    }
}

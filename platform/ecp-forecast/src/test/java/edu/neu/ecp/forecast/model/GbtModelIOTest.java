package edu.neu.ecp.forecast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.forecast.train.SimulatedHistory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GbtModelIOTest {

    /** A small GBT forecaster trained on simulated runs (enough to exercise every part of the file). */
    static GbtForecaster small() {
        List<double[]> x = new ArrayList<>();
        List<Double> d15 = new ArrayList<>();
        List<Double> d30 = new ArrayList<>();
        List<Double> d60 = new ArrayList<>();
        List<Double> w30 = new ArrayList<>();
        for (int s = 0; s < 6; s++) {
            List<MarginPoint> run = SimulatedHistory.run(500 + s, 4);
            for (int t = Features.HISTORY_MINUTES - 1; t + 60 < run.size(); t += 3) {
                x.add(Features.at(run, t));
                double m = run.get(t).marginC();
                d15.add(run.get(t + 15).marginC() - m);
                d30.add(run.get(t + 30).marginC() - m);
                d60.add(run.get(t + 60).marginC() - m);
                w30.add(run.get(t + 30).marginC() < m - 0.5 ? 1.0 : 0.0);
            }
        }
        Map<Integer, Gbt.Model> regs = new LinkedHashMap<>();
        Gbt.Params p = Gbt.Params.of(Gbt.Loss.HUBER, 3, 20).withTrees(25).withHuberDelta(0.3);
        regs.put(15, Gbt.fit(x, arr(d15), null, p));
        regs.put(30, Gbt.fit(x, arr(d30), null, p));
        regs.put(60, Gbt.fit(x, arr(d60), null, p));
        Map<Integer, Gbt.Model> cls = new LinkedHashMap<>();
        Gbt.Params lp = Gbt.Params.of(Gbt.Loss.LOGISTIC, 2, 20).withTrees(20);
        cls.put(30, Gbt.fit(x, arr(w30), null, lp).withThreshold(0.4));
        cls.put(60, Gbt.fit(x, arr(w30), null, lp).withThreshold(0.35));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("trained_on", "unit test \"runs\"");
        metrics.put("mae_60m", 0.5);
        return new GbtForecaster("gbt-test", regs, cls, metrics);
    }

    static double[] arr(List<Double> l) {
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test
    void roundTripsAndForecastsTheSame() {
        GbtForecaster f = small();
        String json = ModelIO.gbtToJson(f);
        GbtForecaster g = ModelIO.gbtFromJson(json);
        assertEquals("gbt-test", g.version());
        assertEquals(0.5, ((Number) g.metrics().get("mae_60m")).doubleValue(), 1e-12);
        assertEquals(0.4, g.classifiers().get(30).threshold(), 1e-12);
        for (int s = 0; s < 3; s++) {
            List<MarginPoint> h = SimulatedHistory.run(9000 + s, 2);
            for (int end = 31; end <= h.size(); end += 7) {
                Forecast a = f.forecast(h.subList(0, end));
                Forecast b = g.forecast(h.subList(0, end));
                assertEquals(a.margin15C(), b.margin15C(), 1e-4);
                assertEquals(a.margin60C(), b.margin60C(), 1e-4);
                assertEquals(a.risk60(), b.risk60(), 1e-4);
                assertEquals("model", b.method());
            }
        }
        // a second write of the read model is byte-identical: the format is stable
        assertEquals(json, ModelIO.gbtToJson(g));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length < 200_000);
    }

    @Test
    void fallsBackToTheTrendWithShortHistory() {
        GbtForecaster f = small();
        List<MarginPoint> h = SimulatedHistory.run(1, 1).subList(0, 10);
        Forecast fc = f.forecast(h);
        assertEquals("trend", fc.method());
        assertEquals("gbt-test", fc.modelVersion());
    }

    @Test
    void rejectsBrokenFiles() {
        assertThrows(IllegalArgumentException.class, () -> ModelIO.gbtFromJson("{\"type\": \"ridge\"}"));
        assertThrows(IllegalArgumentException.class, () -> ModelIO.tree(new double[] {0, -1}, new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> ModelIO.tree(new double[] {-1, -1}, new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> new GbtForecaster("x", Map.of(), Map.of(), Map.of()));
        assertNull(ModelIO.loadBundledGbt("/models/does-not-exist.json"));
    }

    @Test
    void treeDecodesPreorder() {
        // root splits on feature 1 at 0.5; left leaf 1.0; right splits on feature 0 at 2.0 into leaves 2.0 and 3.0
        Gbt.Tree t = ModelIO.tree(new double[] {1, -1, 0, -1, -1}, new double[] {0.5, 1.0, 2.0, 2.0, 3.0});
        assertEquals(1.0, t.predict(new double[] {9, 0.1}), 0);
        assertEquals(2.0, t.predict(new double[] {1, 0.9}), 0);
        assertEquals(3.0, t.predict(new double[] {5, 0.9}), 0);
        assertEquals(2, t.depth());
    }
}

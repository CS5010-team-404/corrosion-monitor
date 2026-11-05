package edu.neu.ecp.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.forecast.model.Features;
import edu.neu.ecp.forecast.model.Forecast;
import edu.neu.ecp.forecast.model.MarginForecaster;
import edu.neu.ecp.forecast.model.MarginPoint;
import edu.neu.ecp.forecast.model.ModelIO;
import edu.neu.ecp.forecast.model.Ridge;
import edu.neu.ecp.forecast.train.SimulatedHistory;
import edu.neu.ecp.forecast.train.Trainer;
import edu.neu.ecp.forecast.train.UciLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ForecastTest {

    @Test
    void ridgeRecoversAKnownLinearFunction() {
        Random r = new Random(1);
        List<double[]> x = new ArrayList<>();
        double[] y = new double[500];
        for (int i = 0; i < 500; i++) {
            double a = r.nextGaussian() * 3, b = r.nextGaussian() + 10;
            x.add(new double[] {a, b});
            y[i] = 2 * a - 3 * b + 1;
        }
        Ridge.LinearModel m = Ridge.fit(x, y, 1e-8);
        assertEquals(2 * 1.5 - 3 * 9.0 + 1, m.predict(new double[] {1.5, 9.0}), 1e-4);
    }

    @Test
    void modelBeatsTheNoChangeBaselineOnUnseenRuns() {
        Trainer.Result r = Trainer.train(40, 12, 6);
        double model = ((Number) r.forecaster().metrics().get("mae_60m")).doubleValue();
        double noChange = ((Number) r.forecaster().metrics().get("mae_no_change_60m")).doubleValue();
        assertTrue(model < noChange * 0.9, "model " + model + " vs no-change " + noChange);
    }

    @Test
    void modelFileRoundTripsAndForecastsTheSame() {
        MarginForecaster f = Trainer.train(20, 4, 4).forecaster();
        MarginForecaster g = ModelIO.fromJson(ModelIO.toJson(f));
        List<MarginPoint> h = SimulatedHistory.run(77, 2);
        Forecast a = f.forecast(h), b = g.forecast(h);
        assertEquals(a.margin60C(), b.margin60C(), 1e-6);
        assertEquals("model", a.method());
        assertEquals(f.version(), g.version());
    }

    @Test
    void shortHistoryFallsBackToTrendAndPredictsCondensationTime() {
        MarginForecaster f = Trainer.train(10, 2, 3).forecaster();
        Instant t0 = Instant.parse("2026-11-02T12:00:00Z");
        List<MarginPoint> falling = new ArrayList<>();
        for (int i = 0; i < 10; i++) { // surface 0.1 °C closer to the dew point every minute
            falling.add(new MarginPoint(t0.plusSeconds(60L * i), 20, 60, 15.0 - 0.1 * i));
        }
        Forecast fc = f.forecast(falling);
        assertEquals("trend", fc.method());
        assertNotNull(fc.predictedCondensationAt());
        List<MarginPoint> flat = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            flat.add(new MarginPoint(t0.plusSeconds(60L * i), 20, 40, 19));
        }
        assertNull(f.forecast(flat).predictedCondensationAt());
    }

    @Test
    void featuresNeedThirtyMinutesOfHistory() {
        List<MarginPoint> h = SimulatedHistory.run(5, 1);
        assertEquals(Features.NAMES.size(), Features.at(h, Features.HISTORY_MINUTES - 1).length);
    }

    @Test
    void uciLoaderInterpolatesTenMinuteRowsToMinutes() throws Exception {
        Path csv = Files.createTempFile("uci", ".csv");
        Files.writeString(csv, "\"date\",\"T1\",\"RH_1\"\n\"2016-01-11 17:00:00\",19.89,47.6\n\"2016-01-11 17:10:00\",19.89,46.7\n\"2016-01-11 17:20:00\",19.89,46.3\n");
        List<MarginPoint> pts = UciLoader.room(csv, 1, 2.0);
        assertEquals(20, pts.size());
        assertEquals(47.15, pts.get(5).humidityPct(), 1e-9);
        assertEquals(17.89, pts.get(0).surfaceC(), 1e-9);
    }
}

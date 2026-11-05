package edu.neu.ecp.forecast.train;

import edu.neu.ecp.forecast.model.MarginPoint;
import edu.neu.ecp.simulator.Scenario;
import edu.neu.ecp.simulator.SimulatedReading;
import edu.neu.ecp.simulator.SimulatedSensor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates one-minute histories from the project's own simulator, with realistic episodes: long normal periods,
 * humid nights and cold-surface events of random length, then recovery. Fault scenarios (stuck, silent ...) are
 * left out on purpose: sensor health filters those readings before analytics ever sees them.
 */
public final class SimulatedHistory {

    /** Failures unfold about six times slower than in the live demo, as they do in real rooms. */
    public static final double REALISTIC_SPEED = 1.0 / 6;

    private SimulatedHistory() {
    }

    public static List<MarginPoint> run(long seed, int hours) {
        Random rnd = new Random(seed);
        double baseTemp = 12 + rnd.nextDouble() * 16;
        double baseRh = 38 + rnd.nextDouble() * 30;
        double offset = 0.8 + rnd.nextDouble() * 2.4;
        SimulatedSensor sensor = new SimulatedSensor("TRAIN-" + seed, "k", baseTemp, baseRh, offset, seed, 0)
                .withScenarioSpeed(REALISTIC_SPEED);
        Instant start = Instant.parse("2026-01-01T00:00:00Z").plus(Duration.ofMinutes(rnd.nextInt(24 * 60)));
        Duration step = Duration.ofSeconds(15);
        int ticks = hours * 240;
        List<MarginPoint> perMinute = new ArrayList<>(hours * 60);
        int nextSwitch = 240 + rnd.nextInt(960); // ticks (15 s)
        for (int i = 0; i < ticks; i++) {
            Instant now = start.plus(step.multipliedBy(i));
            if (i == nextSwitch) {
                Scenario current = sensor.scenario();
                Scenario next = current == Scenario.NORMAL
                        ? (rnd.nextDouble() < 0.55 ? Scenario.HUMID_NIGHT : Scenario.CONDENSATION)
                        : Scenario.NORMAL;
                sensor.setScenario(next, now);
                nextSwitch = i + (next == Scenario.NORMAL ? 480 + rnd.nextInt(1440) : 720 + rnd.nextInt(1440));
            }
            var r = sensor.next(now);
            if (i % 4 == 3 && r.isPresent()) {
                SimulatedReading s = r.get();
                perMinute.add(new MarginPoint(now, s.temperatureC(), s.humidityPct(), s.surfaceTempC()));
            }
        }
        return perMinute;
    }
}

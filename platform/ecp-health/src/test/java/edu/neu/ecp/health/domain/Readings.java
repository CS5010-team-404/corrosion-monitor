package edu.neu.ecp.health.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** Test helpers for building reading windows. */
final class Readings {

    static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");
    static final Duration INTERVAL = Duration.ofSeconds(15);
    static final SensorRef SENSOR = new SensorRef(7L, "SIM-007", 3L, INTERVAL, NOW.minus(Duration.ofDays(1)));

    private Readings() {
    }

    /** {@code count} readings ending at {@code end}, one per interval, gently varying unless {@code values} says otherwise. */
    static List<ReadingSample> series(int count, Instant end, IntFunction<double[]> values) {
        List<ReadingSample> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant t = end.minus(INTERVAL.multipliedBy(count - 1L - i));
            double[] v = values.apply(i);
            list.add(new ReadingSample(1000 + i, t, t.plusMillis(200), v[0], v[1]));
        }
        return list;
    }

    static List<ReadingSample> normal(int count, Instant end) {
        return series(count, end, i -> new double[] {22.0 + 0.03 * Math.sin(i), 48.0 + 0.2 * Math.cos(i)});
    }

    static Observation observe(List<ReadingSample> window) {
        Instant lastSeen = window.isEmpty() ? null : window.get(window.size() - 1).receivedAt();
        return new Observation(SENSOR, window, lastSeen, 0, NOW);
    }
}

package edu.neu.ecp.health.realdata;

import java.time.Duration;
import java.util.List;

/**
 * The heuristic reference the rules are scored against. There are no official fault labels for this dataset, so each
 * reading is labelled by simple, stated physical rules, chosen independently of the health rules' own thresholds:
 * <ul>
 *   <li><b>implausible</b>: temperature outside 0..50 °C or humidity outside 0..100 % for an indoor office lab;</li>
 *   <li><b>low battery</b>: supply voltage below {@link #LOW_VOLTAGE} V, where these motes are known to report garbage
 *       (the dataset's documented failure: temperatures above 100 °C as batteries die);</li>
 *   <li><b>flat</b>: part of a run of exactly repeated temperature and humidity lasting at least one hour.</li>
 * </ul>
 * "Faulty" is any of the three. This is a heuristic, not ground truth.
 */
public final class ReferenceLabels {

    public static final double MIN_TEMP_C = 0;
    public static final double MAX_TEMP_C = 50;
    public static final double LOW_VOLTAGE = 2.3;
    public static final Duration FLAT_MIN = Duration.ofHours(1);

    private ReferenceLabels() {
    }

    public record Labels(boolean[] implausible, boolean[] lowBattery, boolean[] flat) {
        public boolean faulty(int i) {
            return implausible[i] || lowBattery[i] || flat[i];
        }
    }

    public static Labels of(List<IntelLabLoader.Reading> rs) {
        int n = rs.size();
        boolean[] implausible = new boolean[n];
        boolean[] low = new boolean[n];
        boolean[] flat = new boolean[n];
        for (int i = 0; i < n; i++) {
            IntelLabLoader.Reading r = rs.get(i);
            implausible[i] = !(r.temperatureC() >= MIN_TEMP_C && r.temperatureC() <= MAX_TEMP_C
                    && r.humidityPct() >= 0 && r.humidityPct() <= 100);
            low[i] = !(r.voltage() >= LOW_VOLTAGE);
        }
        int start = 0;
        for (int i = 1; i <= n; i++) {
            boolean same = i < n && rs.get(i).temperatureC() == rs.get(start).temperatureC()
                    && rs.get(i).humidityPct() == rs.get(start).humidityPct();
            if (!same) {
                if (Duration.between(rs.get(start).at(), rs.get(i - 1).at()).compareTo(FLAT_MIN) >= 0) {
                    for (int k = start; k < i; k++) {
                        flat[k] = true;
                    }
                }
                start = i;
            }
        }
        return new Labels(implausible, low, flat);
    }
}

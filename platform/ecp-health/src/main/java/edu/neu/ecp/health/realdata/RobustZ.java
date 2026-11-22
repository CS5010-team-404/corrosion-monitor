package edu.neu.ecp.health.realdata;

import java.util.Arrays;
import java.util.List;

/**
 * A simple unsupervised comparison detector: a reading is flagged when its temperature or humidity is more than
 * {@code threshold} robust standard deviations (1.4826 × median absolute deviation) from the median of the previous
 * {@code history} readings of the same mote. Uses past readings only. The MAD has a floor so a perfectly flat history
 * does not flag the smallest real change.
 */
public final class RobustZ {

    public static final int HISTORY = 60;
    public static final double THRESHOLD = 6.0;
    static final double MAD_FLOOR_T = 0.1;
    static final double MAD_FLOOR_RH = 0.5;

    private RobustZ() {
    }

    public static boolean[] flags(List<IntelLabLoader.Reading> rs) {
        return flags(rs, HISTORY, THRESHOLD);
    }

    public static boolean[] flags(List<IntelLabLoader.Reading> rs, int history, double threshold) {
        boolean[] out = new boolean[rs.size()];
        double[] t = new double[history];
        double[] h = new double[history];
        for (int i = history; i < rs.size(); i++) {
            for (int k = 0; k < history; k++) {
                t[k] = rs.get(i - history + k).temperatureC();
                h[k] = rs.get(i - history + k).humidityPct();
            }
            out[i] = z(rs.get(i).temperatureC(), t, MAD_FLOOR_T) > threshold
                    || z(rs.get(i).humidityPct(), h, MAD_FLOOR_RH) > threshold;
        }
        return out;
    }

    static double z(double x, double[] past, double floor) {
        double[] a = past.clone();
        Arrays.sort(a);
        double med = median(a);
        for (int i = 0; i < a.length; i++) {
            a[i] = Math.abs(a[i] - med);
        }
        Arrays.sort(a);
        double mad = Math.max(floor, median(a));
        return Math.abs(x - med) / (1.4826 * mad);
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }
}

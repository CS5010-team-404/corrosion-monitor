package edu.neu.ecp.forecast.model;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Turns the last 30 minutes of one-minute readings into the feature vector the model uses.
 * Kept deliberately small and explainable: every feature is something a technician would look at.
 */
public final class Features {

    /** Minutes of history needed before a forecast can be made. */
    public static final int HISTORY_MINUTES = 31;

    public static final List<String> NAMES = List.of(
            "margin_now", "margin_slope_5m", "margin_slope_15m", "margin_slope_30m",
            "humidity_now", "humidity_slope_15m", "temp_slope_15m", "margin_std_30m",
            "hour_sin", "hour_cos", "margin_slope_2m", "slope5_x_humidity_room", "slope15_x_humidity_room",
            "slope5_x_margin", "margin_floor");

    private Features() {
    }

    /**
     * Features at index {@code t} of a one-minute series (oldest first). Requires {@code t >= HISTORY_MINUTES - 1}.
     */
    public static double[] at(List<MarginPoint> series, int t) {
        if (t < HISTORY_MINUTES - 1 || t >= series.size()) {
            throw new IllegalArgumentException("need " + HISTORY_MINUTES + " minutes of history at index " + t);
        }
        MarginPoint now = series.get(t);
        double m = now.marginC();
        double m5 = series.get(t - 5).marginC();
        double m15 = series.get(t - 15).marginC();
        double m30 = series.get(t - 30).marginC();
        double mean = 0;
        for (int i = t - 29; i <= t; i++) {
            mean += series.get(i).marginC();
        }
        mean /= 30;
        double var = 0;
        for (int i = t - 29; i <= t; i++) {
            double d = series.get(i).marginC() - mean;
            var += d * d;
        }
        double hour = now.at().atZone(ZoneOffset.UTC).getHour() + now.at().atZone(ZoneOffset.UTC).getMinute() / 60.0;
        double m2 = series.get(t - 2).marginC();
        double room = Math.max(0, 100 - now.humidityPct()) / 100.0; // how much further humidity can climb
        double s5 = (m - m5) / 5.0;
        double s15 = (m - m15) / 15.0;
        return new double[] {
            m,
            (m - m5) / 5.0,
            (m - m15) / 15.0,
            (m - m30) / 30.0,
            now.humidityPct() / 100.0,
            (now.humidityPct() - series.get(t - 15).humidityPct()) / 15.0,
            (now.tempC() - series.get(t - 15).tempC()) / 15.0,
            Math.sqrt(var / 30),
            Math.sin(2 * Math.PI * hour / 24),
            Math.cos(2 * Math.PI * hour / 24),
            (m - m2) / 2.0,
            s5 * room,
            s15 * room,
            s5 * Math.min(m, 10) / 10.0,
            Math.min(m, 3.0),
        };
    }
}

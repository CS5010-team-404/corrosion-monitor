package edu.neu.ecp.simulator;

import java.time.Instant;
import java.util.Locale;

/** One reading as the device would send it. */
public record SimulatedReading(long seq, Instant measuredAt, double temperatureC, double humidityPct, Double surfaceTempC) {

    /** JSON for one reading object (the ingestion body wraps these in {"readings":[...]}). */
    public String toJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"seq\":").append(seq)
          .append(",\"measuredAt\":\"").append(measuredAt).append('"')
          .append(",\"temperatureC\":").append(fmt(temperatureC))
          .append(",\"humidityPct\":").append(fmt(humidityPct));
        if (surfaceTempC != null) {
            sb.append(",\"surfaceTempC\":").append(fmt(surfaceTempC));
        }
        return sb.append('}').toString();
    }

    public static String body(Iterable<SimulatedReading> readings) {
        StringBuilder sb = new StringBuilder("{\"readings\":[");
        boolean first = true;
        for (SimulatedReading r : readings) {
            if (!first) {
                sb.append(',');
            }
            sb.append(r.toJson());
            first = false;
        }
        return sb.append("]}").toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

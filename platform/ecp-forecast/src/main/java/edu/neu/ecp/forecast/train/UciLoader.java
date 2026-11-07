package edu.neu.ecp.forecast.train;

import edu.neu.ecp.forecast.model.MarginPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads real indoor data from the UCI "Appliances Energy Prediction" dataset (energydata_complete.csv,
 * doi:10.24432/C5VC8G): temperature T{n} and humidity RH_{n} for rooms 1-9 every 10 minutes.
 * The rooms have no surface sensor, so surface = air − {@code offsetC}. Ten-minute values are linearly
 * interpolated to one-minute points.
 */
public final class UciLoader {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UciLoader() {
    }

    public static List<MarginPoint> room(Path csv, int room, double offsetC) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<String> header = Arrays.stream(lines.get(0).split(",")).map(UciLoader::unquote).toList();
        int iDate = header.indexOf("date");
        int iT = header.indexOf("T" + room);
        int iRh = header.indexOf("RH_" + room);
        if (iDate < 0 || iT < 0 || iRh < 0) {
            throw new IllegalArgumentException("columns date, T" + room + ", RH_" + room + " not found in " + csv);
        }
        List<MarginPoint> tenMin = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.split(",");
            Instant at = LocalDateTime.parse(unquote(c[iDate]), TS).toInstant(ZoneOffset.UTC);
            double t = Double.parseDouble(unquote(c[iT]));
            double rh = Double.parseDouble(unquote(c[iRh]));
            tenMin.add(new MarginPoint(at, t, rh, t - offsetC));
        }
        List<MarginPoint> perMinute = new ArrayList<>();
        for (int i = 0; i + 1 < tenMin.size(); i++) {
            MarginPoint a = tenMin.get(i);
            MarginPoint b = tenMin.get(i + 1);
            long gap = (b.at().getEpochSecond() - a.at().getEpochSecond()) / 60;
            if (gap <= 0 || gap > 30) {
                continue; // skip holes rather than invent data
            }
            for (int k = 0; k < gap; k++) {
                double f = (double) k / gap;
                perMinute.add(new MarginPoint(a.at().plusSeconds(60L * k), lerp(a.tempC(), b.tempC(), f),
                        lerp(a.humidityPct(), b.humidityPct(), f), lerp(a.surfaceC(), b.surfaceC(), f)));
            }
        }
        return perMinute;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static String unquote(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }
}

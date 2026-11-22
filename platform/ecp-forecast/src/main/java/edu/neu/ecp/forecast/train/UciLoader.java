package edu.neu.ecp.forecast.train;

import edu.neu.ecp.forecast.model.MarginPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads real indoor data from the UCI "Appliances Energy Prediction" dataset (energydata_complete.csv,
 * Candanedo, Feldheim &amp; Deramaix 2017, doi:10.24432/C5VC8G, CC BY 4.0): temperature {@code T{n}} and humidity
 * {@code RH_{n}} for locations 1-9 every 10 minutes (location 6 is outside the building).
 *
 * <p>Cleaning follows docs/ML_FORECAST.md "Real-data evaluation protocol": drop, never fill. Per location, rows with
 * an unparsable timestamp or value, duplicate timestamps and physically impossible values are dropped (each counted),
 * then the series is cut into segments wherever two consecutive readings are not exactly 10 minutes apart. Segments
 * too short to give 30 minutes of history and 60 minutes of future are dropped (counted).
 */
public final class UciLoader {

    /** Minutes between readings in the dataset. */
    public static final int STEP_MINUTES = 10;
    /** Locations in the file; 6 is the outdoor sensor. */
    public static final int LOCATIONS = 9;
    public static final int OUTDOOR_LOCATION = 6;
    /** A segment needs 30 min of history and 60 min of future around at least one reading: 10 readings. */
    public static final int MIN_SEGMENT_READINGS = 30 / STEP_MINUTES + 60 / STEP_MINUTES + 1;

    static final double MIN_TEMP_C = -40;
    static final double MAX_TEMP_C = 60;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UciLoader() {
    }

    /** One cleaned reading of one location. Timestamps are the file's local times, read as UTC. */
    public record Reading(Instant at, double tempC, double humidityPct) {
    }

    /** What the cleaning rules removed for one location (rows of the file). */
    public record Cleaning(int rows, int unparsable, int duplicates, int impossible, int segments,
                           int shortSegments, int readingsInShortSegments, int kept) {
    }

    /** A location's cleaned, gap-free segments (each strictly every 10 minutes) and the cleaning counts. */
    public record Location(int id, List<List<Reading>> segments, Cleaning cleaning) {
        public boolean outdoor() {
            return id == OUTDOOR_LOCATION;
        }
    }

    public static Map<Integer, Location> load(Path csv) throws IOException {
        return parse(Files.readAllLines(csv));
    }

    /** Parses the file's lines (header first) into locations 1..9. */
    public static Map<Integer, Location> parse(List<String> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("empty file");
        }
        List<String> header = Arrays.stream(lines.get(0).split(",")).map(UciLoader::unquote).toList();
        int iDate = header.indexOf("date");
        if (iDate < 0) {
            throw new IllegalArgumentException("column date not found");
        }
        Map<Integer, Location> out = new LinkedHashMap<>();
        for (int loc = 1; loc <= LOCATIONS; loc++) {
            int iT = header.indexOf("T" + loc);
            int iRh = header.indexOf("RH_" + loc);
            if (iT < 0 || iRh < 0) {
                throw new IllegalArgumentException("columns T" + loc + ", RH_" + loc + " not found");
            }
            out.put(loc, location(loc, lines.subList(1, lines.size()), iDate, iT, iRh));
        }
        return out;
    }

    private static Location location(int loc, List<String> rows, int iDate, int iT, int iRh) {
        int total = 0;
        int unparsable = 0;
        int duplicates = 0;
        int impossible = 0;
        Set<Instant> seen = new HashSet<>();
        List<Reading> clean = new ArrayList<>();
        for (String line : rows) {
            if (line.isBlank()) {
                continue;
            }
            total++;
            String[] c = line.split(",", -1);
            Instant at;
            double t;
            double rh;
            try {
                at = LocalDateTime.parse(unquote(c[iDate]), TS).toInstant(ZoneOffset.UTC);
                t = Double.parseDouble(unquote(c[iT]));
                rh = Double.parseDouble(unquote(c[iRh]));
            } catch (ArrayIndexOutOfBoundsException | DateTimeParseException | NumberFormatException e) {
                unparsable++;
                continue;
            }
            if (!seen.add(at)) {
                duplicates++;
                continue;
            }
            if (!(t >= MIN_TEMP_C && t <= MAX_TEMP_C && rh > 0 && rh <= 100)) {
                impossible++; // also catches NaN
                continue;
            }
            clean.add(new Reading(at, t, rh));
        }
        List<List<Reading>> segments = new ArrayList<>();
        List<Reading> cur = new ArrayList<>();
        for (Reading r : clean) {
            if (!cur.isEmpty()
                    && r.at().getEpochSecond() - cur.get(cur.size() - 1).at().getEpochSecond() != STEP_MINUTES * 60L) {
                segments.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(r);
        }
        if (!cur.isEmpty()) {
            segments.add(cur);
        }
        int shortSegs = 0;
        int shortReadings = 0;
        List<List<Reading>> kept = new ArrayList<>();
        for (List<Reading> s : segments) {
            if (s.size() < MIN_SEGMENT_READINGS) {
                shortSegs++;
                shortReadings += s.size();
            } else {
                kept.add(List.copyOf(s));
            }
        }
        int keptReadings = kept.stream().mapToInt(List::size).sum();
        return new Location(loc, List.copyOf(kept), new Cleaning(total, unparsable, duplicates, impossible,
                segments.size(), shortSegs, shortReadings, keptReadings));
    }

    /**
     * One-minute points for a gap-free segment: reading {@code i} lands at index {@code 10 i}, the minutes between two
     * readings are linearly interpolated between those two readings only. A point at index {@code k} therefore uses
     * readings at or before index {@code ceil(k / 10) * 10}; forecasts are made only at reading indices, so features
     * never see a later reading (see the leakage test).
     */
    public static List<MarginPoint> perMinute(List<Reading> segment, double offsetC) {
        List<MarginPoint> out = new ArrayList<>((segment.size() - 1) * STEP_MINUTES + 1);
        for (int i = 0; i + 1 < segment.size(); i++) {
            Reading a = segment.get(i);
            Reading b = segment.get(i + 1);
            for (int k = 0; k < STEP_MINUTES; k++) {
                double f = (double) k / STEP_MINUTES;
                double t = lerp(a.tempC(), b.tempC(), f);
                out.add(new MarginPoint(a.at().plusSeconds(60L * k), t, lerp(a.humidityPct(), b.humidityPct(), f),
                        t - offsetC));
            }
        }
        Reading last = segment.get(segment.size() - 1);
        out.add(new MarginPoint(last.at(), last.tempC(), last.humidityPct(), last.tempC() - offsetC));
        return out;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static String unquote(String s) {
        String t = s.trim();
        t = t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
        return t.trim();
    }
}

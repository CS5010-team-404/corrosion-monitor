package edu.neu.ecp.health.realdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Loads the Intel Berkeley Research Lab sensor data (Madden et al., 2004, http://db.csail.mit.edu/labdata/labdata.html):
 * 54 Mica2Dot motes, one reading per 31-second epoch, lines of
 * {@code date time epoch moteid temperature humidity light voltage}. Timestamps are the lab's local times, read as UTC
 * (only differences matter here). Nothing is filled or corrected: faulty values are exactly what the health rules
 * should see. Counted and dropped: lines without all eight fields or that do not parse, mote ids not in
 * {@code mote_locs.txt}, and repeated (mote, epoch) pairs (first kept).
 */
public final class IntelLabLoader {

    private static final DateTimeFormatter TS = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().toFormatter();

    private IntelLabLoader() {
    }

    /** One reading as the mote sent it. {@code epoch} is the network-wide sequence number. */
    public record Reading(Instant at, long epoch, double temperatureC, double humidityPct, double light,
                          double voltage) {
    }

    public record Counts(long lines, long malformed, long unknownMote, long duplicateEpoch, long kept) {
    }

    /** Readings per mote (sorted by time, then epoch) and what was dropped. */
    public record Data(Map<Integer, List<Reading>> motes, Counts counts) {
    }

    /** Mote ids listed in mote_locs.txt ({@code id x y} per line). */
    public static Set<Integer> moteIds(List<String> lines) {
        Set<Integer> ids = new HashSet<>();
        for (String l : lines) {
            String[] f = l.trim().split("\\s+");
            if (f.length >= 1 && !f[0].isEmpty()) {
                ids.add(Integer.parseInt(f[0]));
            }
        }
        return ids;
    }

    public static Data load(Path data, Path moteLocs) throws IOException {
        Set<Integer> ids = moteIds(Files.readAllLines(moteLocs));
        try (InputStream raw = Files.newInputStream(data);
             InputStream in = data.toString().endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw;
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII), 1 << 16)) {
            return parse(r.lines().iterator(), ids);
        }
    }

    public static Data parse(java.util.Iterator<String> lines, Set<Integer> knownMotes) {
        long total = 0;
        long malformed = 0;
        long unknown = 0;
        long dup = 0;
        Map<Integer, List<Reading>> motes = new TreeMap<>();
        Map<Integer, Set<Long>> seen = new TreeMap<>();
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isBlank()) {
                continue;
            }
            total++;
            String[] f = line.trim().split("\\s+");
            if (f.length != 8) {
                malformed++;
                continue;
            }
            Reading reading;
            int mote;
            try {
                Instant at = LocalDateTime.parse(f[0] + " " + f[1], TS).toInstant(ZoneOffset.UTC);
                mote = Integer.parseInt(f[3]);
                reading = new Reading(at, Long.parseLong(f[2]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
                        Double.parseDouble(f[6]), Double.parseDouble(f[7]));
            } catch (DateTimeParseException | NumberFormatException e) {
                malformed++;
                continue;
            }
            if (!knownMotes.contains(mote)) {
                unknown++;
                continue;
            }
            if (!seen.computeIfAbsent(mote, k -> new HashSet<>()).add(reading.epoch())) {
                dup++;
                continue;
            }
            motes.computeIfAbsent(mote, k -> new ArrayList<>()).add(reading);
        }
        long kept = 0;
        for (List<Reading> l : motes.values()) {
            l.sort(Comparator.comparing(Reading::at).thenComparingLong(Reading::epoch));
            kept += l.size();
        }
        return new Data(motes, new Counts(total, malformed, unknown, dup, kept));
    }
}

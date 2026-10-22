package com.team404.corrosion.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class SensorSimulator {
    private final String sensorId;
    private final Random rng;
    private final double baseTempC, baseDewC;
    private final List<ScenarioEvent> events = new ArrayList<>();
    private final List<FaultWindow> faults = new ArrayList<>();

    private double dewWander = 0;      // Ornstein-Uhlenbeck state (C)
    private double lastTemp, lastRh;   // for STUCK
    private boolean haveLast = false;
    private long seq = 0;

    SensorSimulator(String sensorId, long seed, double baseTempC, double baseDewC) {
        this.sensorId = sensorId; this.rng = new Random(seed);
        this.baseTempC = baseTempC; this.baseDewC = baseDewC;
    }

    SensorSimulator addEvent(ScenarioEvent e) { events.add(e); return this; }
    SensorSimulator addFault(FaultWindow f) { faults.add(f); return this; }

    /** TRUE temperature: baseline + daily cycle + HVAC triangle wave + events. */
    double trueTemp(double h) {
        double daily = 2.0 * Math.sin(2 * Math.PI * (h - 9) / 24.0);          // peaks about 15:00
        double phase = ((h * 60) % 15) / 15.0;                                  // 15 min HVAC period
        double tri = phase < 0.5 ? 4 * phase - 1 : 3 - 4 * phase;               // -1..1
        double t = baseTempC + daily + 0.5 * tri;
        for (ScenarioEvent e : events) if (e.target == ScenarioEvent.Target.TEMP) t += e.offsetAt(h);
        return t;
    }

    /** TRUE dew point: baseline + mean-reverting random wander + events. */
    double trueDew(double h, double dtHours) {
        double theta = 0.5, sigma = 0.3;                                         // per hour
        dewWander += -theta * dewWander * dtHours + sigma * Math.sqrt(dtHours) * rng.nextGaussian();
        double d = baseDewC + dewWander;
        for (ScenarioEvent e : events) if (e.target == ScenarioEvent.Target.DEWPOINT) d += e.offsetAt(h);
        return d;
    }

    /** Next measurement, or empty if the sensor is silent (dropout). */
    Optional<Reading> next(double simHours, double dtSeconds, Instant simTime) {
        long thisSeq = seq++;                                                    // seq advances even in dropout, so gaps are visible
        double trueT = trueTemp(simHours);
        double trueD = trueDew(simHours, dtSeconds / 3600.0);
        double trueRh = Psychro.rhFromDewPoint(trueT, trueD);

        double temp = trueT + 0.1 * rng.nextGaussian();                          // sensor noise
        double rh = trueRh + 0.5 * rng.nextGaussian();

        for (FaultWindow f : faults) {
            if (!f.active(simHours)) continue;
            switch (f.type) {
                case DROPOUT -> { return Optional.empty(); }
                case STUCK -> { if (haveLast) { temp = lastTemp; rh = lastRh; } }
                case DRIFT -> rh += f.param * (simHours - f.startH);
                case BAD_VALUES -> { rh = 130; temp = -90; }
            }
        }
        temp = Math.round(temp * 100) / 100.0;                                   // quantize
        rh = Math.round(Math.max(0, Math.min(f2(rh), 100.0)) * 100) / 100.0;
        if (!isBad(faults, simHours)) { lastTemp = temp; lastRh = rh; haveLast = true; }
        return Optional.of(new Reading(sensorId, thisSeq, simTime, temp, rh, false));
    }

    private static double f2(double v) { return v; }
    private static boolean isBad(List<FaultWindow> fs, double h) {
        return fs.stream().anyMatch(f -> f.active(h) && f.type == FaultWindow.Type.BAD_VALUES);
    }

    /** Ground truth for evaluation: first simulated hour where true margin <= 0. */
    OptionalDouble firstCondensationHour(double fromH, double toH, double stepSeconds) {
        double dt = stepSeconds / 3600.0;
        for (double h = fromH; h < toH; h += dt) {
            if (trueTemp(h) - trueDew(h, dt) <= 0) return OptionalDouble.of(h);
        }
        return OptionalDouble.empty();
    }
}

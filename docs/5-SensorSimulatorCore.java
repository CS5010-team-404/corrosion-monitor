// Sensor simulator core. Split each top-level type into its own file.
// Requires Java 17+ (records, HexFormat, java.net.http).

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// ---------------------------------------------------------------------------
// Reading.java
// ---------------------------------------------------------------------------
record Reading(String sensorId, long seq, Instant ts, double temperatureC, double humidityPct, boolean buffered) {
    String toJson() {
        return String.format(Locale.ROOT,
            "{\"sensorId\":\"%s\",\"seq\":%d,\"ts\":\"%s\",\"temperatureC\":%.2f,\"humidityPct\":%.2f,\"buffered\":%b}",
            sensorId, seq, ts, temperatureC, humidityPct, buffered);
    }
}

// ---------------------------------------------------------------------------
// Psychro.java: Magnus formulas (same constants as DewPointCalculator)
// ---------------------------------------------------------------------------
final class Psychro {
    private static final double B = 17.62, C = 243.12;
    static double satPressureHpa(double tC) { return 6.112 * Math.exp(B * tC / (C + tC)); }
    static double rhFromDewPoint(double tC, double tdC) {
        return Math.max(0, Math.min(100, 100.0 * satPressureHpa(tdC) / satPressureHpa(tC)));
    }
}

// ---------------------------------------------------------------------------
// ScenarioEvent.java: adds offsets to the TRUE state using a trapezoid profile
// ---------------------------------------------------------------------------
final class ScenarioEvent {
    enum Target { TEMP, DEWPOINT }

    final Target target;
    final double startH, rampUpH, holdH, rampDownH, delta;

    ScenarioEvent(Target target, double startH, double rampUpH, double holdH, double rampDownH, double delta) {
        this.target = target; this.startH = startH; this.rampUpH = rampUpH;
        this.holdH = holdH; this.rampDownH = rampDownH; this.delta = delta;
    }

    /** Offset in C at simulated hour h (0 before start, delta during hold, back to 0 after). */
    double offsetAt(double h) {
        double t = h - startH;
        if (t <= 0) return 0;
        if (t < rampUpH) return delta * (t / rampUpH);
        t -= rampUpH;
        if (t < holdH) return delta;
        t -= holdH;
        if (t < rampDownH) return delta * (1 - t / rampDownH);
        return 0;
    }
}

// ---------------------------------------------------------------------------
// Sensor faults (applied to the MEASUREMENT, not the physics)
// ---------------------------------------------------------------------------
final class FaultWindow {
    enum Type { STUCK, DROPOUT, DRIFT, BAD_VALUES }
    final Type type; final double startH, endH; final double param; // param: drift %RH per hour
    FaultWindow(Type type, double startH, double endH, double param) {
        this.type = type; this.startH = startH; this.endH = endH; this.param = param;
    }
    boolean active(double h) { return h >= startH && h < endH; }
}

// ---------------------------------------------------------------------------
// SensorSimulator.java
// ---------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------
// Publisher.java: HMAC-signed HTTP posting with offline buffering
// ---------------------------------------------------------------------------
final class Publisher {
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl, sensorId;
    private final byte[] secret;
    private final Deque<Reading> buffer = new ArrayDeque<>();
    boolean networkDown = false;                       // toggled by the NETWORK_OUTAGE scenario

    Publisher(String baseUrl, String sensorId, String secret) {
        this.baseUrl = baseUrl; this.sensorId = sensorId; this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    void publish(Reading r) {
        if (networkDown) { buffer.addLast(r); return; }
        flushBuffer();
        send(r.toJson());
    }

    /** Replay buffered readings with their ORIGINAL seq and buffered=true. */
    private void flushBuffer() {
        while (!buffer.isEmpty()) {
            Reading b = buffer.pollFirst();
            send(new Reading(b.sensorId(), b.seq(), b.ts(), b.temperatureC(), b.humidityPct(), true).toJson());
        }
    }

    private void send(String body) {
        try {
            String nonce = UUID.randomUUID().toString();
            String signature = hmacHex(sensorId + "." + nonce + "." + body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/readings"))
                .header("Content-Type", "application/json")
                .header("X-Sensor-Id", sensorId)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) System.err.println("Rejected: " + resp.statusCode() + " " + resp.body());
        } catch (Exception e) {
            System.err.println("Send failed: " + e.getMessage());
        }
    }

    private String hmacHex(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}

// ---------------------------------------------------------------------------
// SimulatorMain.java: runs the overnight-cooling scenario (S1)
// ---------------------------------------------------------------------------
public class SimulatorMain {
    public static void main(String[] args) throws Exception {
        String sensorId = "6f1c2a9e-3b7d-4c1e-9a55-0d2f7c8e1b10";
        double speed = 720;                 // 720x: one simulated day in 2 real minutes
        double intervalSec = 30;            // simulated seconds between readings
        double durationH = 24;

        SensorSimulator sim = new SensorSimulator(sensorId, 42, 22.0, 12.0)
            // HVAC setback: temp drops 6 C over 2 h, holds 5 h, recovers over 2 h
            .addEvent(new ScenarioEvent(ScenarioEvent.Target.TEMP, 2.0, 2.0, 5.0, 2.0, -6.0))
            // sensor stuck from hour 15 to 16
            .addFault(new FaultWindow(FaultWindow.Type.STUCK, 15.0, 16.0, 0));

        Publisher pub = new Publisher("https://localhost:8443", sensorId, System.getenv("SENSOR_SECRET"));

        Instant simStart = Instant.parse("2026-11-03T00:00:00Z");
        long sleepMs = (long) (intervalSec * 1000 / speed);

        for (double sec = 0; sec < durationH * 3600; sec += intervalSec) {
            double h = sec / 3600.0;
            pub.networkDown = (h >= 8.0 && h < 8.5);          // 30 min outage to test buffering
            Instant simTime = simStart.plusSeconds((long) sec);
            sim.next(h, intervalSec, simTime).ifPresent(pub::publish);
            Thread.sleep(sleepMs);
        }
    }
}

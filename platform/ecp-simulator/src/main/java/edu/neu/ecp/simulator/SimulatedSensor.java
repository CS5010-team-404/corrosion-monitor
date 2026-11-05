package edu.neu.ecp.simulator;

import edu.neu.ecp.common.domain.Hmac;
import edu.neu.ecp.common.domain.Psychrometrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * A believable temperature/humidity sensor. The "true" air state drifts slowly toward a set point with small
 * noise (like a real HVAC-controlled room); scenarios then change either the true state (humid night,
 * condensation) or only what the device reports (stuck, erratic, impossible values, clock skew, bad key).
 *
 * <p>Thread-safe: the engine calls {@link #next(Instant)} on a scheduler thread while an admin may change the scenario.
 */
public final class SimulatedSensor {

    private static final Duration SKEW = Duration.ofMinutes(10);

    private final String deviceId;
    private final String secret;
    private final double baseTempC;
    private final double baseHumidityPct;
    private final double surfaceOffsetC;
    private final Random rng;

    private double tempC;
    private double humidityPct;
    private double surfaceTempC;
    private long seq;
    private Scenario scenario = Scenario.NORMAL;
    private Instant scenarioSince;
    private SimulatedReading last;
    private double speed = 1.0;

    public SimulatedSensor(String deviceId, String secret, double baseTempC, double baseHumidityPct,
                           double surfaceOffsetC, long seed, long startSeq) {
        this.deviceId = deviceId;
        this.secret = secret;
        this.baseTempC = baseTempC;
        this.baseHumidityPct = baseHumidityPct;
        this.surfaceOffsetC = surfaceOffsetC;
        this.rng = new Random(seed);
        this.tempC = baseTempC;
        this.humidityPct = baseHumidityPct;
        this.surfaceTempC = baseTempC - surfaceOffsetC;
        this.seq = startSeq;
    }

    public String deviceId() {
        return deviceId;
    }

    /**
     * How fast failure scenarios unfold. 1.0 (default) is demo speed: a humid night takes about 20 minutes.
     * Realistic rooms change roughly 6-8 times slower; the forecaster is trained at speed 1/6.
     */
    public synchronized SimulatedSensor withScenarioSpeed(double factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("speed must be positive");
        }
        this.speed = factor;
        return this;
    }

    public synchronized Scenario scenario() {
        return scenario;
    }

    public synchronized void setScenario(Scenario next, Instant now) {
        if (next != scenario) {
            scenario = next;
            scenarioSince = now;
        }
    }

    /** The next reading at {@code now}, or empty if this tick produces nothing (silent or lost). */
    public synchronized Optional<SimulatedReading> next(Instant now) {
        if (scenarioSince == null) {
            scenarioSince = now;
        }
        if (scenario == Scenario.SILENT) {
            return Optional.empty();
        }
        if (scenario == Scenario.LOSSY && rng.nextDouble() < 0.4) {
            seq++; // the device sent it, the network lost it: the gap shows up in sequence numbers
            evolve(now);
            return Optional.empty();
        }
        if (scenario == Scenario.STUCK && last != null) {
            seq++;
            last = new SimulatedReading(seq, now, last.temperatureC(), last.humidityPct(), last.surfaceTempC());
            return Optional.of(last);
        }

        evolve(now);
        seq++;
        double reportedTemp = round2(tempC);
        double reportedRh = round2(Math.max(0.5, Math.min(100.0, humidityPct)));
        double reportedSurface = round2(surfaceTempC);

        switch (scenario) {
            case ERRATIC -> {
                if (rng.nextDouble() < 0.3) {
                    reportedTemp = round2(reportedTemp + (rng.nextBoolean() ? 1 : -1) * (6 + rng.nextDouble() * 5));
                }
                if (rng.nextDouble() < 0.2) {
                    reportedRh = round2(Math.max(1, Math.min(99, reportedRh + (rng.nextBoolean() ? 25 : -25))));
                }
            }
            case OUT_OF_RANGE -> {
                if (rng.nextDouble() < 0.5) {
                    reportedRh = round2(101 + rng.nextDouble() * 9);
                }
            }
            default -> {
                // no reporting fault
            }
        }
        Instant measuredAt = scenario == Scenario.CLOCK_SKEW ? now.minus(SKEW) : now;
        last = new SimulatedReading(seq, measuredAt, reportedTemp, reportedRh, reportedSurface);
        return Optional.of(last);
    }

    /** Body and signature for a request carrying {@code readings}, signed at {@code epochSeconds}. */
    public synchronized SignedRequest sign(List<SimulatedReading> readings, long epochSeconds) {
        String body = SimulatedReading.body(readings);
        String key = scenario == Scenario.BAD_SIGNATURE ? secret + "-wrong" : secret;
        return new SignedRequest(deviceId, epochSeconds, body, Hmac.signRequest(key, epochSeconds, body));
    }

    public record SignedRequest(String deviceId, long epochSeconds, String body, String signature) {
    }

    // ------------------------------------------------------------------ physics

    private void evolve(Instant now) {
        double minutes = speed * Duration.between(scenarioSince, now).toMillis() / 60_000.0;
        double targetTemp = baseTempC;
        double targetRh = baseHumidityPct;
        double targetSurface = baseTempC - surfaceOffsetC;

        if (scenario == Scenario.HUMID_NIGHT) {
            targetRh = Math.min(88.0, baseHumidityPct + 2.2 * minutes);
            targetTemp = baseTempC - Math.min(2.0, 0.08 * minutes);
        } else if (scenario == Scenario.CONDENSATION) {
            targetRh = Math.min(72.0, baseHumidityPct + 1.5 * minutes);
            double dew = Psychrometrics.dewPointC(tempC, Math.max(1.0, Math.min(100.0, humidityPct)));
            double drop = Math.min(1.0, minutes / 10.0); // reaches the dew point after ~10 minutes
            targetSurface = (baseTempC - surfaceOffsetC) * (1 - drop) + (dew - 0.8) * drop;
        }

        tempC += 0.08 * (targetTemp - tempC) + gaussian(0.04);
        humidityPct += 0.10 * (targetRh - humidityPct) + gaussian(0.15);
        surfaceTempC += 0.15 * (targetSurface - surfaceTempC) + gaussian(0.03);
        humidityPct = Math.max(1.0, Math.min(99.5, humidityPct));
    }

    private double gaussian(double sd) {
        return rng.nextGaussian() * sd;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

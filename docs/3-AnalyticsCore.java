// Analytics core: dew point, condensation margin, Peck-based corrosion risk index.
// Split each top-level class into its own file in your Spring Boot project
// (e.g. package com.team.corrosion.analytics). Shown together here for readability.

// ---------------------------------------------------------------------------
// DewPointCalculator.java
// ---------------------------------------------------------------------------
public final class DewPointCalculator {

    // Magnus-Sonntag constants, valid roughly -45 C to 60 C
    private static final double B = 17.62;
    private static final double C = 243.12; // degrees C

    private DewPointCalculator() {}

    /** Dew point in C from air temperature (C) and relative humidity (%). */
    public static double dewPointC(double tempC, double rhPct) {
        if (rhPct <= 0 || rhPct > 100) {
            throw new IllegalArgumentException("RH must be in (0, 100], got " + rhPct);
        }
        double gamma = Math.log(rhPct / 100.0) + (B * tempC) / (C + tempC);
        return (C * gamma) / (B - gamma);
    }

    /**
     * Condensation margin: how far the coldest relevant surface is above the dew point.
     * Margin <= 0 means condensation is occurring. Use ambient temp if surface temp is unknown
     * (and say so in your report); use ambient minus a configured offset to be conservative.
     */
    public static double marginC(double surfaceTempC, double dewPointC) {
        return surfaceTempC - dewPointC;
    }
}

// ---------------------------------------------------------------------------
// PeckRiskIndex.java
// ---------------------------------------------------------------------------
/**
 * Corrosion risk based on Peck's temperature-humidity acceleration model:
 *
 *   AF = (RH / RH_ref)^n * exp( (Ea / k) * (1/T_ref - 1/T) )     (T in Kelvin)
 *
 * AF = how many times faster damage accumulates than at the reference condition.
 * Default n and Ea are commonly cited for Peck's model (n ~ 2.66, Ea ~ 0.79 eV) but
 * vary by failure mechanism: cite your source and keep them configurable.
 *
 * Damage accumulates as sum(AF * dtHours). The risk score is damage / budget, where
 * budget is a configurable value per equipment profile. This is a RELATIVE exposure index,
 * not a prediction of actual time to failure.
 */
public final class PeckRiskIndex {

    private static final double BOLTZMANN_EV_PER_K = 8.617333262e-5;

    private final double n;
    private final double eaEv;
    private final double refTempK;
    private final double refRhPct;

    public PeckRiskIndex(double n, double eaEv, double refTempC, double refRhPct) {
        this.n = n;
        this.eaEv = eaEv;
        this.refTempK = refTempC + 273.15;
        this.refRhPct = refRhPct;
    }

    /** Acceleration factor for one reading. */
    public double accelerationFactor(double tempC, double rhPct) {
        double tK = tempC + 273.15;
        double rhTerm = Math.pow(Math.max(rhPct, 1.0) / refRhPct, n);
        double tempTerm = Math.exp((eaEv / BOLTZMANN_EV_PER_K) * (1.0 / refTempK - 1.0 / tK));
        return rhTerm * tempTerm;
    }

    /** Damage units added by one reading that represents dtSeconds of exposure. */
    public double damage(double tempC, double rhPct, double dtSeconds) {
        return accelerationFactor(tempC, rhPct) * (dtSeconds / 3600.0);
    }

    /** Risk score, 0 = no exposure, 1.0 = budget used up (can exceed 1). */
    public static double score(double cumulativeDamage, double budget) {
        return cumulativeDamage / budget;
    }

    public static RiskLevel level(double score) {
        if (score < 0.5) return RiskLevel.LOW;
        if (score < 0.8) return RiskLevel.ELEVATED;
        if (score < 1.0) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }

    public enum RiskLevel { LOW, ELEVATED, HIGH, CRITICAL }
}

// ---------------------------------------------------------------------------
// RiskAccumulator.java: stateful per-location accumulation (sketch)
// ---------------------------------------------------------------------------
public class RiskAccumulator {

    private final PeckRiskIndex model;
    private final double budget;
    private double cumulativeDamage;

    public RiskAccumulator(PeckRiskIndex model, double budget) {
        this.model = model;
        this.budget = budget;
    }

    /**
     * Call once per reading. dtSeconds is the time since the previous reading, capped
     * (e.g. 5 minutes) so a long sensor outage is not counted as exposure we never measured.
     */
    public double update(double tempC, double rhPct, double dtSeconds) {
        double cappedDt = Math.min(dtSeconds, 300);
        cumulativeDamage += model.damage(tempC, rhPct, cappedDt);
        return PeckRiskIndex.score(cumulativeDamage, budget);
    }

    /** Optional: call on a schedule to model a rolling window instead of all-time exposure. */
    public void decay(double factor) { cumulativeDamage *= factor; }

    public double cumulativeDamage() { return cumulativeDamage; }
}

// ---------------------------------------------------------------------------
// Simple forecasting: linear regression over a sliding window (baseline model)
// ---------------------------------------------------------------------------
public final class LinearForecaster {

    /**
     * times in seconds (relative to the first sample), values same length.
     * Returns the predicted value at (lastTime + horizonSeconds).
     */
    public static double forecast(double[] times, double[] values, double horizonSeconds) {
        int n = times.length;
        if (n < 3) throw new IllegalArgumentException("need at least 3 points");
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += times[i];
            sumY += values[i];
            sumXY += times[i] * values[i];
            sumXX += times[i] * times[i];
        }
        double denom = n * sumXX - sumX * sumX;
        double slope = (denom == 0) ? 0 : (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return intercept + slope * (times[n - 1] + horizonSeconds);
    }

    /** Mean Absolute Error between predictions and actual values. */
    public static double mae(double[] predicted, double[] actual) {
        double sum = 0;
        for (int i = 0; i < predicted.length; i++) sum += Math.abs(predicted[i] - actual[i]);
        return sum / predicted.length;
    }
}

// ---------------------------------------------------------------------------
// Sensor fault detection (sketch)
// ---------------------------------------------------------------------------
public final class SensorHealthChecker {

    public enum Health { OK, SILENT, STUCK, IMPLAUSIBLE }

    /** silentAfterSec: e.g. 3x the expected interval. stuckWindow: e.g. last 20 readings. */
    public static Health check(java.time.Instant lastSeen, java.time.Instant now,
                               long silentAfterSec, double[] recentTemps, double[] recentRh) {
        if (java.time.Duration.between(lastSeen, now).getSeconds() > silentAfterSec) {
            return Health.SILENT;
        }
        for (int i = 0; i < recentTemps.length; i++) {
            if (recentRh[i] < 0 || recentRh[i] > 100 || recentTemps[i] < -40 || recentTemps[i] > 125) {
                return Health.IMPLAUSIBLE;
            }
        }
        if (recentTemps.length >= 20 && isFlat(recentTemps) && isFlat(recentRh)) {
            return Health.STUCK; // real sensors always show a little noise
        }
        return Health.OK;
    }

    private static boolean isFlat(double[] v) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double x : v) { min = Math.min(min, x); max = Math.max(max, x); }
        return (max - min) < 1e-9;
    }
}

// ---------------------------------------------------------------------------
// DewPointCalculatorTest.java (JUnit 5)
// ---------------------------------------------------------------------------
/*
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AnalyticsCoreTest {

    @Test
    void dewPointMatchesReferenceValues() {
        // Reference values from standard psychrometric tables (verify against your own source)
        assertEquals(16.7, DewPointCalculator.dewPointC(25, 60), 0.5);
        assertEquals(9.3,  DewPointCalculator.dewPointC(20, 50), 0.5);
        assertEquals(20.0, DewPointCalculator.dewPointC(20, 100), 0.1); // saturation: dew point = temp
    }

    @Test
    void marginIsNegativeWhenCondensing() {
        assertTrue(DewPointCalculator.marginC(15.0, 16.7) < 0);
    }

    @Test
    void accelerationFactorIsOneAtReference() {
        PeckRiskIndex m = new PeckRiskIndex(2.66, 0.79, 25, 40);
        assertEquals(1.0, m.accelerationFactor(25, 40), 1e-9);
    }

    @Test
    void humidAndWarmDamagesFasterThanDryAndCool() {
        PeckRiskIndex m = new PeckRiskIndex(2.66, 0.79, 25, 40);
        assertTrue(m.accelerationFactor(30, 80) > m.accelerationFactor(20, 40));
    }

    @Test
    void linearForecastExtrapolatesTrend() {
        double[] t = {0, 60, 120, 180};
        double[] v = {50, 52, 54, 56};            // +2 per minute
        assertEquals(66.0, LinearForecaster.forecast(t, v, 300), 1e-6); // +5 min from t=180
    }

    @Test
    void maeIsAverageAbsoluteDifference() {
        assertEquals(2.0, LinearForecaster.mae(new double[]{62, 58, 65}, new double[]{60, 61, 64}), 1e-9);
    }
}
*/

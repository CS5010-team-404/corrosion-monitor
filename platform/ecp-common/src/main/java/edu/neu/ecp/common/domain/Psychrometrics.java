package edu.neu.ecp.common.domain;

/**
 * Moisture math shared by every service. Pure functions, no framework code, so it is trivial to unit test.
 *
 * <p>Dew point uses the Magnus formula with the Sonntag (1990) constants a = 17.62 and b = 243.12 °C,
 * which keeps the error well inside the proposal's ±0.5 °C target for -45..60 °C.
 */
public final class Psychrometrics {

    public static final double MAGNUS_A = 17.62;
    public static final double MAGNUS_B = 243.12;

    private Psychrometrics() {
    }

    /** Dew point in °C for air temperature {@code tempC} and relative humidity {@code rhPct} (0 < rh ≤ 100). */
    public static double dewPointC(double tempC, double rhPct) {
        if (rhPct <= 0 || rhPct > 100) {
            throw new IllegalArgumentException("relative humidity must be in (0, 100], was " + rhPct);
        }
        double gamma = Math.log(rhPct / 100.0) + (MAGNUS_A * tempC) / (MAGNUS_B + tempC);
        return (MAGNUS_B * gamma) / (MAGNUS_A - gamma);
    }

    /**
     * Condensation margin in °C: how far the surface is above the dew point.
     * Zero or below means water condenses on the surface.
     */
    public static double marginC(double surfaceTempC, double dewPointC) {
        return surfaceTempC - dewPointC;
    }

    /**
     * Surface temperature to use when a sensor does not measure it directly: the air temperature minus the
     * equipment profile's cold-spot offset (for example, metal near an air-conditioning outlet runs colder than the air).
     */
    public static double effectiveSurfaceTempC(Double measuredSurfaceC, double airTempC, double profileOffsetC) {
        return measuredSurfaceC != null ? measuredSurfaceC : airTempC - profileOffsetC;
    }

    /** ISO 9223 time-of-wetness condition: RH above 80 % while the temperature is above 0 °C. */
    public static boolean isTimeOfWetness(double tempC, double rhPct) {
        return rhPct > 80.0 && tempC > 0.0;
    }
}

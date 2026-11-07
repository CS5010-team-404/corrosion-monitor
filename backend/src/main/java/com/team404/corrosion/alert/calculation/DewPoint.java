package com.team404.corrosion.alert.calculation;

/**
 * Dew point from air temperature and relative humidity using the Magnus formula.
 * Valid for roughly -45 C to 60 C.
 */

/**
 * Dew point from air temperature and relative humidity using the Magnus formula.

 * The Magnus formula approximates saturation vapour pressure with a curve fitted to measured data:
 * Es(T) = 6.112 * exp(b * T / (c + T)). Solving "Es(dew point) = RH * Es(T)" for the dew point gives:

 *   magnusTerm = ln(RH / 100) + b * T / (c + T)
 *   dewPoint   = c * magnusTerm / (b - magnusTerm)

 * The constants b and c below are the Sonntag (1990) values (to confirm before citing formally).
 * Other published fits differ slightly (for example Alduchov and Eskridge 1996: 17.625 and 243.04),
 * but all agree to a fraction of a degree. Use one set everywhere in the project.

 * Valid for air temperatures of roughly -45 C to 60 C. Outside that range the result is less reliable.
 */
public final class DewPoint {

    /** Magnus formula coefficient */
    private static final double MAGNUS_COEFFICIENT = 17.62;

    /** Magnus formula offset in degrees Celsius. */
    private static final double MAGNUS_OFFSET_CELSIUS = 243.12;

    private DewPoint() {
    }

    /** Dew point in degrees Celsius. Relative humidity must be above 0 and at most 100 (percent). */
    public static double celsius(double airTemperatureC, double relativeHumidityPct) {
        if (Double.isNaN(airTemperatureC) || Double.isNaN(relativeHumidityPct)) {
            throw new IllegalArgumentException("temperature and humidity must be numbers");
        }
        if (relativeHumidityPct <= 0 || relativeHumidityPct > 100) {
            throw new IllegalArgumentException(
                    "relative humidity must be above 0 and at most 100, got " + relativeHumidityPct);
        }

        double humidityFraction = relativeHumidityPct / 100.0;
        double magnusTerm = Math.log(humidityFraction)
                + (MAGNUS_COEFFICIENT * airTemperatureC) / (MAGNUS_OFFSET_CELSIUS + airTemperatureC);

        return (MAGNUS_OFFSET_CELSIUS * magnusTerm) / (MAGNUS_COEFFICIENT - magnusTerm);
    }

    /**
     * Margin between a surface temperature and the dew point. Condensation risk grows as this
     * approaches 0. Until a real surface sensor exists, pass the air temperature as the surface temperature.
     */
    public static double margin(double surfaceTemperatureC, double airTemperatureC, double relativeHumidityPct) {
        double dewPointC = celsius(airTemperatureC, relativeHumidityPct);
        return surfaceTemperatureC - dewPointC;
    }
}

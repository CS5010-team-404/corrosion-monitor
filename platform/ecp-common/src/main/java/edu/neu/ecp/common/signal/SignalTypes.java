package edu.neu.ecp.common.signal;

/** Agreed signal type names. Add new ones here so every service uses the same spelling. */
public final class SignalTypes {
    // Analytics (Aayushee)
    public static final String CONDENSATION_RISK = "CONDENSATION_RISK";
    public static final String HIGH_HUMIDITY = "HIGH_HUMIDITY";
    public static final String TEMPERATURE_OUT_OF_RANGE = "TEMPERATURE_OUT_OF_RANGE";
    public static final String CONDENSATION_FORECAST = "CONDENSATION_FORECAST";
    // Sensor health (Joshua)
    public static final String SENSOR_OFFLINE = "SENSOR_OFFLINE";
    public static final String SENSOR_STUCK = "SENSOR_STUCK";
    public static final String SENSOR_OUT_OF_RANGE = "SENSOR_OUT_OF_RANGE";
    public static final String SENSOR_ERRATIC = "SENSOR_ERRATIC";
    public static final String SENSOR_LOSSY = "SENSOR_LOSSY";
    public static final String SENSOR_SIGNATURE_FAILURES = "SENSOR_SIGNATURE_FAILURES";
    public static final String SENSOR_CLOCK_SKEW = "SENSOR_CLOCK_SKEW";
    // Platform
    public static final String SERVICE_DOWN = "SERVICE_DOWN";

    private SignalTypes() {
    }
}

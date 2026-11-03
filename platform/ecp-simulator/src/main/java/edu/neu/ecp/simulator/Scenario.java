package edu.neu.ecp.simulator;

/** Behaviours an admin can switch a simulated sensor into from the dashboard (Admin → Simulator). */
public enum Scenario {
    NORMAL("Normal room conditions with small natural variation"),
    HUMID_NIGHT("Dehumidifier fails: humidity climbs toward 88 % over about 20 minutes"),
    CONDENSATION("Cold surface (e.g. AC drip line): surface temperature falls below the dew point"),
    SILENT("Sensor stops sending (power or network loss)"),
    STUCK("Sensor keeps repeating its last values"),
    ERRATIC("Loose wiring: random large jumps"),
    OUT_OF_RANGE("Broken element: physically impossible values"),
    LOSSY("Flaky network: about 40 % of readings are lost"),
    BAD_SIGNATURE("Wrong key: requests fail signature checks"),
    CLOCK_SKEW("Device clock is 10 minutes behind");

    private final String description;

    Scenario(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}

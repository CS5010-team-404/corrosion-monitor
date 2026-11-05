package edu.neu.ecp.health.domain;

import edu.neu.ecp.common.signal.Severity;
import edu.neu.ecp.common.signal.SignalTypes;

/**
 * Problems the sensor health service can detect. Each issue knows how serious it is, which alert signal it
 * raises, whether readings from an affected sensor can still be trusted by analytics, and how much it lowers
 * the 0-100 health score.
 */
public enum HealthIssue {

    /** Silent: no accepted reading for longer than the offline threshold ("silent" in the proposal). */
    OFFLINE(Severity.CRITICAL, SignalTypes.SENSOR_OFFLINE, false, 100, true,
            "Sensor offline", "No readings received for %s."),

    /** Flatline: the last N readings are identical ("stuck" in the proposal). */
    STUCK(Severity.CRITICAL, SignalTypes.SENSOR_STUCK, false, 70, false,
            "Sensor stuck", "The last %s readings did not change at all."),

    /** Values outside what the sensor can physically measure (e.g. RH above 100 %). */
    OUT_OF_RANGE(Severity.WARNING, SignalTypes.SENSOR_OUT_OF_RANGE, false, 50, false,
            "Impossible readings", "%s readings were outside the sensor's measurable range."),

    /** Repeated jumps that real air cannot make between two readings. */
    ERRATIC(Severity.WARNING, SignalTypes.SENSOR_ERRATIC, false, 40, false,
            "Erratic readings", "%s sudden jumps in the last window."),

    /** Sequence-number gaps: readings are being lost on the way. */
    LOSSY(Severity.WARNING, SignalTypes.SENSOR_LOSSY, true, 25, false,
            "Readings going missing", "%s%% of expected readings did not arrive."),

    /** Requests arriving with bad signatures: misconfigured device or someone spoofing it. */
    SIGNATURE_FAILURES(Severity.WARNING, SignalTypes.SENSOR_SIGNATURE_FAILURES, true, 20, false,
            "Rejected signatures", "%s requests failed signature checks."),

    /** Device clock disagrees with the server clock, so timestamps (and forecasts) are unreliable. */
    CLOCK_SKEW(Severity.WARNING, SignalTypes.SENSOR_CLOCK_SKEW, true, 15, false,
            "Device clock is off", "Device clock differs from server time by %s seconds.");

    private final Severity severity;
    private final String signalType;
    private final boolean dataStillTrusted;
    private final int scorePenalty;
    private final boolean clearsImmediately;
    private final String title;
    private final String messageTemplate;

    HealthIssue(Severity severity, String signalType, boolean dataStillTrusted, int scorePenalty,
                boolean clearsImmediately, String title, String messageTemplate) {
        this.severity = severity;
        this.signalType = signalType;
        this.dataStillTrusted = dataStillTrusted;
        this.scorePenalty = scorePenalty;
        this.clearsImmediately = clearsImmediately;
        this.title = title;
        this.messageTemplate = messageTemplate;
    }

    public Severity severity() {
        return severity;
    }

    public String signalType() {
        return signalType;
    }

    /** False when analytics must ignore this sensor's readings while the issue is open. */
    public boolean dataStillTrusted() {
        return dataStillTrusted;
    }

    public int scorePenalty() {
        return scorePenalty;
    }

    /**
     * True when one clean evaluation is proof of recovery (a reading arriving ends OFFLINE).
     * Other issues need {@link HealthPolicy#recoveryEvaluations()} clean evaluations in a row, so they do not flap.
     */
    public boolean clearsImmediately() {
        return clearsImmediately;
    }

    public String title() {
        return title;
    }

    public String message(Object detail) {
        return messageTemplate.formatted(detail);
    }
}

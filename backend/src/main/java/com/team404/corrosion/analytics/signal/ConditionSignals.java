package com.team404.corrosion.analytics.signal;

import com.team404.corrosion.analytics.risk.RiskLevel;
import java.util.Optional;

/**
 * When analytics raises and clears condensation signals. Plain Java: it only decides, the job writes the rows.
 *
 * <p>Both signals work the same way, per equipment item and dedup key:
 * <ul>
 *   <li>when the condition starts, RAISED once; nothing more is written while it stays;</li>
 *   <li>when the condition ends, CLEARED once;</li>
 *   <li>when there is no trusted data (level UNKNOWN, no fresh forecast) nothing is written, neither RAISED nor
 *       CLEARED: bad data must not open or close an alert, sensor health reports that sensor by itself.</li>
 * </ul>
 * The only repeat is for the risk signal when it moves between WARNING and CRITICAL: a new RAISED with the new
 * severity, which the alert service uses to update the open alert (severity follows the level).
 * Whether an episode is open is read from the last signal written for the key, so it survives a restart.
 */
public final class ConditionSignals {

    public static final String RAISED = "RAISED";
    public static final String CLEARED = "CLEARED";

    /** Raise the forecast signal when the probability of reaching the critical margin within 60 minutes is this high. */
    public static final double FORECAST_PROBABILITY_THRESHOLD = 0.5;

    public static final String WARNING = "WARNING";

    /** What to write: state RAISED or CLEARED, with the severity to put on the row. */
    public record Decision(String state, String severity) {
    }

    private ConditionSignals() {
    }

    /**
     * The condensation risk signal (key ANALYTICS:CONDENSATION_RISK:id). WARNING and CRITICAL raise it with the
     * level as severity, NORMAL clears it.
     *
     * @param last the last signal written for this key, or null if there is none
     */
    public static Optional<Decision> forRisk(RiskLevel level, SignalStore.LastSignal last) {
        boolean open = isOpen(last);
        switch (level) {
            case WARNING, CRITICAL -> {
                String severity = level.name();
                if (open && severity.equals(last.severity())) {
                    return Optional.empty();
                }
                return Optional.of(new Decision(RAISED, severity));
            }
            case NORMAL -> {
                return open ? Optional.of(new Decision(CLEARED, last.severity())) : Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * The condensation forecast signal (key ANALYTICS:CONDENSATION_FORECAST:id), always WARNING. Raised when the
     * 60 minute warning probability reaches the threshold, cleared when it falls below it.
     *
     * @param warningProbability60 from a fresh forecast row, or null when there is none (then nothing is written)
     * @param last                 the last signal written for this key, or null if there is none
     */
    public static Optional<Decision> forForecast(Double warningProbability60, SignalStore.LastSignal last) {
        if (warningProbability60 == null) {
            return Optional.empty();
        }
        boolean open = isOpen(last);
        boolean condition = warningProbability60 >= FORECAST_PROBABILITY_THRESHOLD;
        if (condition) {
            return open ? Optional.empty() : Optional.of(new Decision(RAISED, WARNING));
        }
        return open ? Optional.of(new Decision(CLEARED, WARNING)) : Optional.empty();
    }

    private static boolean isOpen(SignalStore.LastSignal last) {
        return last != null && RAISED.equals(last.state());
    }
}

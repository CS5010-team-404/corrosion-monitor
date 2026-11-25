package com.team404.corrosion.analytics.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.team404.corrosion.analytics.risk.RiskLevel;
import com.team404.corrosion.analytics.signal.ConditionSignals.Decision;
import com.team404.corrosion.analytics.signal.SignalStore.LastSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConditionSignalsTest {

    private static final LastSignal RAISED_WARNING = new LastSignal("RAISED", "WARNING");
    private static final LastSignal RAISED_CRITICAL = new LastSignal("RAISED", "CRITICAL");
    private static final LastSignal CLEARED_CRITICAL = new LastSignal("CLEARED", "CRITICAL");

    private static Optional<Decision> raised(String severity) {
        return Optional.of(new Decision("RAISED", severity));
    }

    private static Optional<Decision> cleared(String severity) {
        return Optional.of(new Decision("CLEARED", severity));
    }

    /** Plays a series of levels the way the job does: every decision becomes the "last signal" of the next one. */
    private static List<String> play(RiskLevel... levels) {
        List<String> written = new ArrayList<>();
        LastSignal last = null;
        for (RiskLevel level : levels) {
            Optional<Decision> decision = ConditionSignals.forRisk(level, last);
            if (decision.isPresent()) {
                written.add(decision.get().state() + " " + decision.get().severity());
                last = new LastSignal(decision.get().state(), decision.get().severity());
            }
        }
        return written;
    }

    // ----------------------------------------------------------------------------------------------- risk: enter

    @Test
    void enteringWarningRaisesOnceWithWarningSeverity() {
        assertEquals(raised("WARNING"), ConditionSignals.forRisk(RiskLevel.WARNING, null));
    }

    @Test
    void enteringCriticalRaisesWithCriticalSeverity() {
        assertEquals(raised("CRITICAL"), ConditionSignals.forRisk(RiskLevel.CRITICAL, null));
    }

    @Test
    void aNewEpisodeAfterAClearRaisesAgain() {
        assertEquals(raised("WARNING"), ConditionSignals.forRisk(RiskLevel.WARNING, CLEARED_CRITICAL));
    }

    // ----------------------------------------------------------------------------------------------- risk: stay

    @Test
    void stayingInWarningWritesNothing() {
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.WARNING, RAISED_WARNING));
    }

    @Test
    void stayingInCriticalWritesNothing() {
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.CRITICAL, RAISED_CRITICAL));
    }

    @Test
    void severityFollowsTheLevelWhenItMovesBetweenWarningAndCritical() {
        assertEquals(raised("CRITICAL"), ConditionSignals.forRisk(RiskLevel.CRITICAL, RAISED_WARNING));
        assertEquals(raised("WARNING"), ConditionSignals.forRisk(RiskLevel.WARNING, RAISED_CRITICAL));
    }

    // ----------------------------------------------------------------------------------------------- risk: leave

    @Test
    void returningToNormalClearsWithTheSeverityItWasRaisedWith() {
        assertEquals(cleared("CRITICAL"), ConditionSignals.forRisk(RiskLevel.NORMAL, RAISED_CRITICAL));
        assertEquals(cleared("WARNING"), ConditionSignals.forRisk(RiskLevel.NORMAL, RAISED_WARNING));
    }

    @Test
    void normalWithNothingOpenWritesNothing() {
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.NORMAL, null));
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.NORMAL, CLEARED_CRITICAL));
    }

    // ----------------------------------------------------------------------------------------------- risk: no data

    @Test
    void anUnknownLevelNeverWritesASignal() {
        // no trusted reading (an untrusted sensor, no sensor, impossible data): neither raise nor clear
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.UNKNOWN, null));
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.UNKNOWN, RAISED_CRITICAL));
        assertEquals(Optional.empty(), ConditionSignals.forRisk(RiskLevel.UNKNOWN, CLEARED_CRITICAL));
    }

    // ----------------------------------------------------------------------------------------------- risk: flapping

    @Test
    void anEpisodeIsOneRaisedAndOneClearedWhateverHappensInBetween() {
        assertEquals(List.of("RAISED WARNING", "CLEARED WARNING"), play(
                RiskLevel.NORMAL, RiskLevel.WARNING, RiskLevel.WARNING, RiskLevel.WARNING, RiskLevel.NORMAL,
                RiskLevel.NORMAL));
    }

    @Test
    void aFlappingConditionWritesOnePairPerEpisodeAndNeverADuplicate() {
        assertEquals(List.of("RAISED WARNING", "CLEARED WARNING", "RAISED WARNING", "CLEARED WARNING",
                "RAISED WARNING"), play(RiskLevel.WARNING, RiskLevel.NORMAL, RiskLevel.WARNING, RiskLevel.NORMAL,
                RiskLevel.WARNING, RiskLevel.WARNING));
    }

    @Test
    void flickeringBetweenWarningAndCriticalWritesOnlyTheChanges() {
        assertEquals(List.of("RAISED WARNING", "RAISED CRITICAL", "RAISED WARNING"), play(
                RiskLevel.WARNING, RiskLevel.WARNING, RiskLevel.CRITICAL, RiskLevel.CRITICAL, RiskLevel.WARNING));
    }

    @Test
    void aMomentWithoutTrustedDataDoesNotCloseOrReopenTheEpisode() {
        assertEquals(List.of("RAISED CRITICAL", "CLEARED CRITICAL"), play(
                RiskLevel.CRITICAL, RiskLevel.UNKNOWN, RiskLevel.UNKNOWN, RiskLevel.CRITICAL, RiskLevel.NORMAL));
    }

    @Test
    void anUntrustedSensorFromTheStartWritesNothingAtAll() {
        assertTrue(play(RiskLevel.UNKNOWN, RiskLevel.UNKNOWN, RiskLevel.UNKNOWN).isEmpty());
    }

    // ----------------------------------------------------------------------------------------------- forecast

    @Test
    void theForecastSignalIsRaisedAtHalfAndNotBefore() {
        assertEquals(Optional.empty(), ConditionSignals.forForecast(0.49, null));
        assertEquals(raised("WARNING"), ConditionSignals.forForecast(0.5, null));
        assertEquals(raised("WARNING"), ConditionSignals.forForecast(0.93, CLEARED_CRITICAL));
    }

    @Test
    void theForecastSignalIsNotRepeatedWhileItStaysLikely() {
        assertEquals(Optional.empty(), ConditionSignals.forForecast(0.6, RAISED_WARNING));
        assertEquals(Optional.empty(), ConditionSignals.forForecast(0.99, RAISED_WARNING));
    }

    @Test
    void theForecastSignalClearsWhenTheProbabilityFallsBelowHalf() {
        assertEquals(cleared("WARNING"), ConditionSignals.forForecast(0.3, RAISED_WARNING));
        assertEquals(Optional.empty(), ConditionSignals.forForecast(0.3, null));
        assertEquals(Optional.empty(), ConditionSignals.forForecast(0.3, CLEARED_CRITICAL));
    }

    @Test
    void noFreshForecastWritesNothingEvenWhileAnEpisodeIsOpen() {
        assertEquals(Optional.empty(), ConditionSignals.forForecast(null, null));
        assertEquals(Optional.empty(), ConditionSignals.forForecast(null, RAISED_WARNING));
    }
}

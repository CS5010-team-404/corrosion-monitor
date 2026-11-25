package com.team404.corrosion.analytics.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.team404.corrosion.analytics.AnalyticsRepo;
import com.team404.corrosion.analytics.AnalyticsService;
import com.team404.corrosion.analytics.AssessmentRepo;
import com.team404.corrosion.analytics.EquipmentSnap;
import com.team404.corrosion.analytics.FakeAssessmentRepo;
import com.team404.corrosion.analytics.Reading;
import com.team404.corrosion.analytics.SensorForecast;
import com.team404.corrosion.analytics.risk.RiskClassifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** The job's decisions and what it writes, with in-memory stand-ins for the database. */
class AnalyticsJobTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    /** The snapshots the job will see; a test changes them between runs to move time forward. */
    private static final class FakeAnalyticsRepo implements AnalyticsRepo {
        List<EquipmentSnap> snapshots = new ArrayList<>();

        @Override
        public List<EquipmentSnap> loadSnapshots(Instant since) {
            return snapshots;
        }
    }

    private static final class FakeSignalStore implements SignalStore {
        final Map<String, LastSignal> last = new HashMap<>();
        final List<NewSignal> written = new ArrayList<>();

        @Override
        public Map<String, LastSignal> lastSignals() {
            return last;
        }

        @Override
        public void append(NewSignal signal) {
            written.add(signal);
            last.put(signal.dedupKey(), new LastSignal(signal.state(), signal.severity()));
        }
    }

    /** A transaction manager that does nothing, so the template just runs the callback. */
    private static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }

    private final FakeAnalyticsRepo analyticsRepo = new FakeAnalyticsRepo();
    private final FakeAssessmentRepo assessments = new FakeAssessmentRepo();
    private final FakeSignalStore signals = new FakeSignalStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AnalyticsJob job = new AnalyticsJob(new AnalyticsService(analyticsRepo, assessments, clock),
            assessments, signals, new TransactionTemplate(new NoTransactions()), clock);

    private static Reading reading(int secondsAgo, double tempC, double rhPct, double surfaceTempC) {
        return new Reading(NOW.minusSeconds(secondsAgo), tempC, rhPct, surfaceTempC);
    }

    /** 25 C at 50 % RH with a 20 C surface: margin about 6 C, NORMAL. */
    private static Reading dry(int secondsAgo) {
        return reading(secondsAgo, 25, 50, 20.0);
    }

    /** 25 C at 55 % RH with a 17.5 C surface: margin about 1.8 C, WARNING. */
    private static Reading shrinking(int secondsAgo) {
        return reading(secondsAgo, 25, 55, 17.5);
    }

    /** 25 C at 90 % RH with a 20 C surface: the surface is below the dew point, CRITICAL. */
    private static Reading humid(int secondsAgo) {
        return reading(secondsAgo, 25, 90, 20.0);
    }

    private static EquipmentSnap item(long id, boolean trusted, List<Reading> readings, SensorForecast forecast) {
        return new EquipmentSnap(id, "Rack " + id, "Server Room A", 1, "IT equipment (ASHRAE)", "it", 18, 27, 1.5,
                new RiskClassifier.Thresholds(3.0, 1.0, 60.0, 70.0), 100, 2.66, 0.79, 25, 40, 70 + id, "SIM-" + id,
                trusted, readings, forecast);
    }

    private static SensorForecast forecast(double probability60) {
        return new SensorForecast(3.0, 2.0, 1.0, 0.3, probability60, "ML", NOW.plusSeconds(1500), NOW.minusSeconds(30));
    }

    private void sees(EquipmentSnap... snaps) {
        analyticsRepo.snapshots = List.of(snaps);
    }

    // ----------------------------------------------------------------------------------------------- condensation risk

    @Test
    void raisesTheRiskSignalOnceInTheSharedRowFormat() {
        sees(item(4, true, List.of(humid(60), humid(0)), null));

        AnalyticsJob.Result result = job.runOnce();

        assertEquals(1, result.raised());
        assertEquals(1, signals.written.size());
        SignalStore.NewSignal s = signals.written.get(0);
        assertEquals("CONDENSATION_RISK", s.type());
        assertEquals("CRITICAL", s.severity());
        assertEquals("RAISED", s.state());
        assertEquals(4L, s.equipmentId());
        assertEquals(74L, s.sensorId());
        assertEquals("ANALYTICS:CONDENSATION_RISK:4", s.dedupKey());
        assertEquals(NOW, s.createdAt());
        assertTrue(s.title().contains("Rack 4"), s.title());
        assertTrue(s.message().contains("Dew-point margin is -"), s.message());
        assertEquals("SIM-4", s.payload().get("deviceId"));       // the alert service reads this
        assertEquals("CRITICAL", s.payload().get("level"));
    }

    @Test
    void doesNotRepeatTheSignalWhileTheLevelStays() {
        sees(item(4, true, List.of(humid(0)), null));

        job.runOnce();
        AnalyticsJob.Result second = job.runOnce();
        AnalyticsJob.Result third = job.runOnce();

        assertEquals(1, signals.written.size());
        assertEquals(0, second.raised() + second.cleared());
        assertEquals(0, third.raised() + third.cleared());
    }

    @Test
    void clearsWhenTheLevelReturnsToNormal() {
        sees(item(4, true, List.of(shrinking(0)), null));
        job.runOnce();

        sees(item(4, true, List.of(shrinking(120), dry(0)), null));
        AnalyticsJob.Result result = job.runOnce();

        assertEquals(1, result.cleared());
        assertEquals(2, signals.written.size());
        SignalStore.NewSignal cleared = signals.written.get(1);
        assertEquals("CLEARED", cleared.state());
        assertEquals("WARNING", cleared.severity());
        assertEquals("ANALYTICS:CONDENSATION_RISK:4", cleared.dedupKey());
        assertTrue(cleared.message().contains("back to"), cleared.message());
    }

    @Test
    void severityFollowsTheLevel() {
        sees(item(4, true, List.of(shrinking(0)), null));
        job.runOnce();
        sees(item(4, true, List.of(humid(0)), null));
        job.runOnce();

        assertEquals(List.of("WARNING", "CRITICAL"), signals.written.stream().map(SignalStore.NewSignal::severity).toList());
        assertEquals(List.of("RAISED", "RAISED"), signals.written.stream().map(SignalStore.NewSignal::state).toList());
    }

    @Test
    void anUntrustedSensorWritesNoSignalAndNoAssessment() {
        // the repository gives an untrusted sensor no readings, so the item is UNKNOWN
        sees(item(4, false, List.of(), null));

        AnalyticsJob.Result result = job.runOnce();

        assertEquals(new AnalyticsJob.Result(0, 0, 0, 0), result);
        assertTrue(signals.written.isEmpty());
        assertTrue(assessments.inserted.isEmpty());
    }

    @Test
    void aSensorThatGoesUntrustedLeavesAnOpenEpisodeOpen() {
        sees(item(4, true, List.of(humid(0)), null));
        job.runOnce();

        sees(item(4, false, List.of(), null));
        job.runOnce();
        job.runOnce();

        assertEquals(1, signals.written.size());   // no CLEARED, no repeated RAISED
    }

    @Test
    void everyItemIsEvaluatedOnItsOwn() {
        sees(item(1, true, List.of(dry(0)), null), item(2, true, List.of(humid(0)), null),
                item(3, true, List.of(shrinking(0)), null));

        AnalyticsJob.Result result = job.runOnce();

        assertEquals(2, result.raised());
        assertEquals(List.of("ANALYTICS:CONDENSATION_RISK:2", "ANALYTICS:CONDENSATION_RISK:3"),
                signals.written.stream().map(SignalStore.NewSignal::dedupKey).toList());
        assertEquals(3, result.assessmentsStored());
    }

    // ----------------------------------------------------------------------------------------------- forecast

    @Test
    void raisesTheForecastSignalAtHalfProbabilityAndNotTwice() {
        sees(item(4, true, List.of(dry(0)), forecast(0.5)));

        job.runOnce();
        job.runOnce();

        assertEquals(1, signals.written.size());
        SignalStore.NewSignal s = signals.written.get(0);
        assertEquals("CONDENSATION_FORECAST", s.type());
        assertEquals("WARNING", s.severity());
        assertEquals("RAISED", s.state());
        assertEquals("ANALYTICS:CONDENSATION_FORECAST:4", s.dedupKey());
        assertEquals("SIM-4", s.payload().get("deviceId"));
        assertEquals(0.5, (Double) s.payload().get("warningProbability60"), 1e-9);
        assertTrue(s.message().contains("50 %"), s.message());
    }

    @Test
    void doesNotRaiseTheForecastSignalBelowHalfProbability() {
        sees(item(4, true, List.of(dry(0)), forecast(0.49)));
        job.runOnce();
        assertTrue(signals.written.isEmpty());
    }

    @Test
    void clearsTheForecastSignalWhenTheProbabilityFalls() {
        sees(item(4, true, List.of(dry(0)), forecast(0.8)));
        job.runOnce();
        sees(item(4, true, List.of(dry(0)), forecast(0.2)));
        job.runOnce();

        assertEquals(List.of("RAISED", "CLEARED"), signals.written.stream().map(SignalStore.NewSignal::state).toList());
    }

    @Test
    void aStaleForecastWritesNothing() {
        SensorForecast stale = new SensorForecast(3.0, 2.0, 1.0, 0.3, 0.9, "ML", null, NOW.minusSeconds(10 * 60));
        sees(item(4, true, List.of(dry(0)), stale));
        job.runOnce();
        assertTrue(signals.written.isEmpty());
    }

    @Test
    void theRiskAndForecastSignalsAreIndependent() {
        sees(item(4, true, List.of(humid(0)), forecast(0.9)));
        job.runOnce();
        assertEquals(List.of("ANALYTICS:CONDENSATION_RISK:4", "ANALYTICS:CONDENSATION_FORECAST:4"),
                signals.written.stream().map(SignalStore.NewSignal::dedupKey).toList());
    }

    // ----------------------------------------------------------------------------------------------- assessments

    @Test
    void storesAnAssessmentForAnItemWithATrustedReading() {
        sees(item(4, true, List.of(dry(60), dry(0)), null));

        AnalyticsJob.Result result = job.runOnce();

        assertEquals(1, result.assessmentsStored());
        AssessmentRepo.Row row = assessments.inserted.get(0);
        assertEquals(4L, row.equipmentId());
        assertEquals(NOW, row.assessedAt());
        assertEquals(NOW, row.measuredAt());
        assertEquals(25.0, row.temperatureC(), 1e-9);
        assertEquals(20.0, row.surfaceTempC(), 1e-9);
        assertEquals(50.0, row.rhPct(), 1e-9);
        assertEquals(13.85, row.dewPointC(), 0.01);
        assertEquals(6.15, row.marginC(), 0.01);
        assertEquals("NORMAL", row.level());
        assertTrue(row.riskIndex() > 0);
    }

    @Test
    void doesNotStoreTheSameReadingTwice() {
        sees(item(4, true, List.of(dry(0)), null));
        job.runOnce();
        AnalyticsJob.Result again = job.runOnce();

        assertEquals(1, assessments.inserted.size());
        assertEquals(0, again.assessmentsStored());
    }

    @Test
    void storesANewerReadingOnTheNextRun() {
        sees(item(4, true, List.of(dry(120)), null));
        job.runOnce();
        sees(item(4, true, List.of(dry(120), dry(0)), null));
        job.runOnce();

        assertEquals(2, assessments.inserted.size());
        assertEquals(NOW.minusSeconds(120), assessments.inserted.get(0).measuredAt());
        assertEquals(NOW, assessments.inserted.get(1).measuredAt());
    }

    @Test
    void keepsOnlySevenDays() {
        sees(item(4, true, List.of(dry(0)), null));
        assessments.pruneCount = 17;

        AnalyticsJob.Result result = job.runOnce();

        assertEquals(NOW.minus(Duration.ofDays(7)), assessments.prunedBefore);
        assertEquals(17, result.assessmentsPruned());
    }

    // ----------------------------------------------------------------------------------------------- failures

    @Test
    void aFailedPassIsLoggedNotThrownWhenRunByTheScheduler() {
        sees(item(4, true, List.of(humid(0)), null));
        assessments.failOnInsert = new IllegalStateException("database is down");

        AnalyticsJob.Result result = job.run();

        assertEquals(new AnalyticsJob.Result(0, 0, 0, 0), result);
    }

    @Test
    void runOnceLetsTheFailureThroughSoTestsSeeIt() {
        sees(item(4, true, List.of(humid(0)), null));
        assessments.failOnInsert = new IllegalStateException("database is down");

        assertThrows(IllegalStateException.class, job::runOnce);
    }
}

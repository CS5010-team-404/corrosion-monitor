package com.team404.corrosion.alert.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team404.corrosion.alert.AlertEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignalProcessorTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");
    private static final Duration QUIET = Duration.ofMinutes(10);
    private static final String KEY = "ANALYTICS:CONDENSATION_RISK:4";

    private final FakeStore store = new FakeStore();
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    };
    private final List<String> events = new ArrayList<>();
    private final List<Long> updates = new ArrayList<>();
    private final AlertEvents recorder = new AlertEvents() {
        @Override public void record(String action, long id, String actor, Map<String, Object> details) {
            events.add(action + ":" + id + ":" + actor);
        }
        @Override public void updated(long id) { updates.add(id); }
    };
    private final SignalProcessor processor = new SignalProcessor(store, recorder, clock, QUIET);

    @Test
    void raisedOpensAnAlert() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));

        assertThat(store.alerts).hasSize(1);
        assertThat(store.alerts.get(0).severity).isEqualTo("WARNING");
        assertThat(store.alerts.get(0).openedAt).isEqualTo(T0);
    }

    @Test
    void raisedAgainUpdatesTheSameAlertInsteadOfOpeningAnother() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(signal(2, Signal.RAISED, "CRITICAL", T0.plusSeconds(30)));

        assertThat(store.alerts).hasSize(1);
        assertThat(store.alerts.get(0).severity).isEqualTo("CRITICAL");
    }

    @Test
    void differentKeysGetDifferentAlerts() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(withKey(signal(2, Signal.RAISED, "WARNING", T0), "HEALTH:SENSOR_OFFLINE:7"));

        assertThat(store.alerts).hasSize(2);
    }

    @Test
    void clearedRecordsWhenTheConditionClearedButLeavesTheAlertOpen() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        Instant clearedAt = T0.plusSeconds(60);
        processor.apply(signal(2, Signal.CLEARED, "WARNING", clearedAt));

        assertThat(store.alerts.get(0).conditionClearedAt).isEqualTo(clearedAt);
        assertThat(store.alerts.get(0).resolved).isFalse();
    }

    @Test
    void aSecondClearedKeepsTheFirstClearTime() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(signal(2, Signal.CLEARED, "WARNING", T0.plusSeconds(60)));
        processor.apply(signal(3, Signal.CLEARED, "WARNING", T0.plusSeconds(120)));

        assertThat(store.alerts.get(0).conditionClearedAt).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void clearedWithNoAlertChangesNothing() {
        processor.apply(signal(1, Signal.CLEARED, "WARNING", T0));

        assertThat(store.alerts).isEmpty();
    }

    @Test
    void resolvesOnlyAfterTheQuietPeriod() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(signal(2, Signal.CLEARED, "WARNING", T0.plusSeconds(60)));

        now = T0.plusSeconds(60).plus(QUIET).minusSeconds(1);
        assertThat(processor.resolveQuiet()).isZero();
        assertThat(store.alerts.get(0).resolved).isFalse();

        now = T0.plusSeconds(60).plus(QUIET);
        assertThat(processor.resolveQuiet()).isEqualTo(1);
        assertThat(store.alerts.get(0).resolved).isTrue();
        assertThat(store.alerts.get(0).resolvedAt).isEqualTo(now);
    }

    @Test
    void aFlappingConditionKeepsOneAlertAndNeverResolvesWhileItKeepsReturning() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        for (int i = 0; i < 5; i++) {
            now = now.plus(Duration.ofMinutes(4));
            processor.apply(signal(10 + 2 * i, Signal.CLEARED, "WARNING", now));
            now = now.plus(Duration.ofMinutes(4));
            processor.apply(signal(11 + 2 * i, Signal.RAISED, "WARNING", now));
            assertThat(processor.resolveQuiet()).isZero();
        }

        assertThat(store.alerts).hasSize(1);
        assertThat(store.alerts.get(0).conditionClearedAt).isNull();
        assertThat(store.alerts.get(0).resolved).isFalse();
    }

    @Test
    void raisedAfterResolutionOpensANewAlert() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(signal(2, Signal.CLEARED, "WARNING", T0));
        now = T0.plus(QUIET);
        processor.resolveQuiet();

        processor.apply(signal(3, Signal.RAISED, "WARNING", now));

        assertThat(store.alerts).hasSize(2);
        assertThat(store.alerts.get(0).resolved).isTrue();
        assertThat(store.alerts.get(1).resolved).isFalse();
    }

    @Test
    void recordsAnEventWhenAnAlertOpensAndWhenItAutoResolvesButNotOnRepeats() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(signal(2, Signal.RAISED, "CRITICAL", T0.plusSeconds(30)));
        processor.apply(signal(3, Signal.CLEARED, "CRITICAL", T0.plusSeconds(60)));
        assertThat(events).containsExactly("ALERT_OPENED:0:null");

        now = T0.plusSeconds(60).plus(QUIET);
        processor.resolveQuiet();
        processor.resolveQuiet();

        assertThat(events).containsExactly("ALERT_OPENED:0:null", "ALERT_RESOLVED:0:null");
    }

    @Test
    void aRefreshAndAFirstClearedTellTheDashboardButAreNotAuditEvents() {
        processor.apply(signal(1, Signal.RAISED, "WARNING", T0));
        processor.apply(signal(2, Signal.RAISED, "CRITICAL", T0.plusSeconds(30)));
        processor.apply(signal(3, Signal.CLEARED, "CRITICAL", T0.plusSeconds(60)));
        processor.apply(signal(4, Signal.CLEARED, "CRITICAL", T0.plusSeconds(90)));

        assertThat(updates).containsExactly(0L, 0L);
        assertThat(events).containsExactly("ALERT_OPENED:0:null");
    }

    @Test
    void anUnknownStateIsRejected() {
        assertThatThrownBy(() -> processor.apply(signal(1, "MAYBE", "WARNING", T0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeQuietPeriodIsRejected() {
        assertThatThrownBy(() -> new SignalProcessor(store, recorder, clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Signal signal(long id, String state, String severity, Instant createdAt) {
        return new Signal(id, "ANALYTICS", "CONDENSATION_RISK", severity, state, 4L, null, null, KEY,
                "Condensation risk", "Margin is low", createdAt);
    }

    private static Signal withKey(Signal s, String key) {
        return new Signal(s.id(), s.source(), s.type(), s.severity(), s.state(), s.equipmentId(), s.sensorId(), s.deviceId(), key,
                s.title(), s.message(), s.createdAt());
    }

    private static final class FakeAlert {
        final String key;
        String severity;
        final Instant openedAt;
        Instant conditionClearedAt;
        boolean resolved;
        Instant resolvedAt;

        FakeAlert(String key, String severity, Instant openedAt) {
            this.key = key;
            this.severity = severity;
            this.openedAt = openedAt;
        }
    }

    private static final class FakeStore implements AlertStore {
        final List<FakeAlert> alerts = new ArrayList<>();

        @Override
        public Optional<StoredAlert> findUnresolved(String dedupKey) {
            for (int i = 0; i < alerts.size(); i++) {
                FakeAlert a = alerts.get(i);
                if (a.key.equals(dedupKey) && !a.resolved) {
                    return Optional.of(new StoredAlert(i, a.conditionClearedAt));
                }
            }
            return Optional.empty();
        }

        @Override
        public long open(Signal s, Instant openedAt) {
            alerts.add(new FakeAlert(s.dedupKey(), s.severity(), openedAt));
            return alerts.size() - 1;
        }

        @Override
        public void refresh(long id, Signal s) {
            FakeAlert a = alerts.get((int) id);
            a.severity = s.severity();
            a.conditionClearedAt = null;
        }

        @Override
        public void markConditionCleared(long id, Instant clearedAt) {
            alerts.get((int) id).conditionClearedAt = clearedAt;
        }

        @Override
        public List<Long> resolveQuiet(Instant clearedBefore, Instant resolvedAt) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < alerts.size(); i++) {
                FakeAlert a = alerts.get(i);
                if (!a.resolved && a.conditionClearedAt != null && !a.conditionClearedAt.isAfter(clearedBefore)) {
                    a.resolved = true;
                    a.resolvedAt = resolvedAt;
                    ids.add((long) i);
                }
            }
            return ids;
        }
    }
}

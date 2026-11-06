package com.team404.corrosion.alert.signal;

import com.team404.corrosion.alert.AlertEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Turns signals into alert state, one signal at a time and in id order:
 * <ul>
 *   <li>RAISED opens an alert, or updates the unresolved one with the same dedup key (so a repeat never adds a row);</li>
 *   <li>CLEARED only records when the condition cleared. The alert stays open until the condition has been quiet
 *       for {@code quietPeriod} ({@link #resolveQuiet()}), and a RAISED in between cancels it. A sensor that flaps
 *       therefore keeps one alert instead of opening and resolving a new one each time.</li>
 * </ul>
 * A CLEARED signal with no unresolved alert, or for an alert already marked cleared, changes nothing.
 */
public class SignalProcessor {

    private final AlertStore store;
    private final AlertEvents events;
    private final Clock clock;
    private final Duration quietPeriod;

    public SignalProcessor(AlertStore store, AlertEvents events, Clock clock, Duration quietPeriod) {
        if (quietPeriod.isNegative()) {
            throw new IllegalArgumentException("quietPeriod must not be negative");
        }
        this.store = store;
        this.events = events;
        this.clock = clock;
        this.quietPeriod = quietPeriod;
    }

    public void apply(Signal signal) {
        var unresolved = store.findUnresolved(signal.dedupKey());
        switch (signal.state()) {
            case Signal.RAISED -> unresolved.ifPresentOrElse(
                    a -> {
                        store.refresh(a.id(), signal, clock.instant());
                        events.updated(a.id());
                    },
                    () -> {
                        long id = store.open(signal, clock.instant());
                        events.record(AlertEvents.OPENED, id, null, Map.of("dedupKey", signal.dedupKey(),
                                "type", signal.type(), "severity", signal.severity()));
                    });
            case Signal.CLEARED -> unresolved
                    .filter(a -> a.conditionClearedAt() == null)
                    .ifPresent(a -> {
                        store.markConditionCleared(a.id(), signal.createdAt());
                        events.updated(a.id());
                    });
            default -> throw new IllegalArgumentException("Unknown signal state: " + signal.state());
        }
    }

    /** Resolves alerts whose condition has stayed cleared for the quiet period. Returns how many. */
    public int resolveQuiet() {
        Instant now = clock.instant();
        List<Long> resolved = store.resolveQuiet(now.minus(quietPeriod), now);
        resolved.forEach(id -> events.record(AlertEvents.RESOLVED, id, null, Map.of("reason", "CONDITION_CLEARED")));
        return resolved.size();
    }
}

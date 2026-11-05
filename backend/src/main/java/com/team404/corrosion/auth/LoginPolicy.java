package com.team404.corrosion.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * Account lockout rules, free of Spring and the database so they are easy to test.
 *
 * <ul>
 *   <li>While an account is locked, every attempt is refused, even with the right password, and is not counted.</li>
 *   <li>A wrong password adds one failure; the fifth consecutive failure locks the account for 15 minutes.</li>
 *   <li>A correct password resets the failures and clears the lock.</li>
 *   <li>Once a lock has expired the count starts again from zero.</li>
 * </ul>
 */
public final class LoginPolicy {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    public enum Outcome { SUCCESS, BAD_CREDENTIALS, LOCKED, NEWLY_LOCKED }

    /** What app_user stores: failed_logins and locked_until (null when not locked). */
    public record State(int failedLogins, Instant lockedUntil) {
        public static final State CLEAN = new State(0, null);

        public boolean lockedAt(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }
    }

    public record Decision(Outcome outcome, State next) {
    }

    public Decision decide(State current, boolean passwordMatches, Instant now) {
        if (current.lockedAt(now)) {
            return new Decision(Outcome.LOCKED, current);
        }
        if (passwordMatches) {
            return new Decision(Outcome.SUCCESS, State.CLEAN);
        }
        int failures = (current.lockedUntil() != null ? 0 : current.failedLogins()) + 1;
        if (failures >= MAX_FAILURES) {
            return new Decision(Outcome.NEWLY_LOCKED, new State(failures, now.plus(LOCK_DURATION)));
        }
        return new Decision(Outcome.BAD_CREDENTIALS, new State(failures, null));
    }
}

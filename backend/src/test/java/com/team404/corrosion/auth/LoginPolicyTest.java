package com.team404.corrosion.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.team404.corrosion.auth.LoginPolicy.Outcome;
import com.team404.corrosion.auth.LoginPolicy.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginPolicyTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private final LoginPolicy policy = new LoginPolicy();

    @Test
    void fourFailuresCountButDoNotLock() {
        State s = State.CLEAN;
        for (int i = 1; i <= 4; i++) {
            LoginPolicy.Decision d = policy.decide(s, false, T0);
            assertThat(d.outcome()).isEqualTo(Outcome.BAD_CREDENTIALS);
            assertThat(d.next()).isEqualTo(new State(i, null));
            s = d.next();
        }
    }

    @Test
    void fifthConsecutiveFailureLocksForFifteenMinutes() {
        LoginPolicy.Decision d = policy.decide(new State(4, null), false, T0);
        assertThat(d.outcome()).isEqualTo(Outcome.NEWLY_LOCKED);
        assertThat(d.next().lockedUntil()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
    }

    @Test
    void lockedAccountRefusesEvenTheRightPasswordAndDoesNotCount() {
        State locked = new State(5, T0.plus(Duration.ofMinutes(15)));
        LoginPolicy.Decision d = policy.decide(locked, true, T0.plus(Duration.ofMinutes(14)));
        assertThat(d.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(d.next()).isEqualTo(locked);
    }

    @Test
    void lockEndsExactlyAtLockedUntil() {
        State locked = new State(5, T0.plus(Duration.ofMinutes(15)));
        assertThat(policy.decide(locked, true, T0.plus(Duration.ofMinutes(15))).outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void afterAnExpiredLockTheCountStartsAgain() {
        State expired = new State(5, T0);
        LoginPolicy.Decision d = policy.decide(expired, false, T0.plus(Duration.ofMinutes(1)));
        assertThat(d.outcome()).isEqualTo(Outcome.BAD_CREDENTIALS);
        assertThat(d.next()).isEqualTo(new State(1, null));
    }

    @Test
    void successResetsFailures() {
        LoginPolicy.Decision d = policy.decide(new State(3, null), true, T0);
        assertThat(d.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(d.next()).isEqualTo(State.CLEAN);
    }
}

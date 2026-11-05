package com.team404.corrosion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Lockout through AuthService with a clock the test controls. */
class AuthServiceLockoutTest {

    private static final String EMAIL = "tech@corrosionwatch.app";
    private static final String RIGHT = "right-password";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final LoginAudit audit = mock(LoginAudit.class);
    private AuthService auth;
    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser("Tariq Technician", EMAIL, encoder.encode(RIGHT), Role.TECHNICIAN, clock.instant());
        when(users.findForLoginByEmail(EMAIL)).thenReturn(Optional.of(user));
        auth = new AuthService(users, encoder, audit, clock);
    }

    @Test
    void fiveFailuresLockForFifteenMinutesThenTheLockExpires() {
        for (int i = 0; i < 4; i++) {
            assertThat(auth.login(EMAIL, "wrong")).isInstanceOf(AuthService.LoginResult.BadCredentials.class);
        }
        AuthService.LoginResult fifth = auth.login(EMAIL, "wrong");
        assertThat(fifth).isInstanceOf(AuthService.LoginResult.Locked.class);
        Instant until = ((AuthService.LoginResult.Locked) fifth).until();
        assertThat(until).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        verify(audit).record(eq(LoginAudit.ACCOUNT_LOCKED), eq(EMAIL), any(), anyMap());

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(auth.login(EMAIL, RIGHT)).as("right password during the lock")
                .isInstanceOf(AuthService.LoginResult.Locked.class);

        clock.advance(Duration.ofSeconds(1));
        assertThat(auth.login(EMAIL, RIGHT)).as("right password once the lock has ended")
                .isInstanceOf(AuthService.LoginResult.Success.class);
        assertThat(user.loginState()).isEqualTo(LoginPolicy.State.CLEAN);
    }

    @Test
    void successResetsTheFailureCount() {
        auth.login(EMAIL, "wrong");
        auth.login(EMAIL, "wrong");
        auth.login(EMAIL, "wrong");
        assertThat(user.loginState().failedLogins()).isEqualTo(3);

        assertThat(auth.login(EMAIL, RIGHT)).isInstanceOf(AuthService.LoginResult.Success.class);
        assertThat(user.loginState()).isEqualTo(LoginPolicy.State.CLEAN);

        // a fresh run of four failures does not lock
        for (int i = 0; i < 4; i++) {
            assertThat(auth.login(EMAIL, "wrong")).isInstanceOf(AuthService.LoginResult.BadCredentials.class);
        }
    }

    @Test
    void unknownEmailIsBadCredentialsAndTouchesNoUser() {
        when(users.findForLoginByEmail("nobody@corrosionwatch.app")).thenReturn(Optional.empty());
        AuthService.LoginResult r = auth.login("  Nobody@CorrosionWatch.app ", "whatever");
        assertThat(r).isEqualTo(new AuthService.LoginResult.BadCredentials("nobody@corrosionwatch.app", null, true));
        verify(audit, never()).record(eq(LoginAudit.USER_LOGIN), any(), any(), anyMap());
    }
}

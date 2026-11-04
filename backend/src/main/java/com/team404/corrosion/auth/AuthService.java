package com.team404.corrosion.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks email and password against app_user and applies {@link LoginPolicy}. Returns a result instead of throwing,
 * so the updated failure count is committed even when the login is refused.
 */
@Service
public class AuthService {

    /** Result of one login attempt. */
    public sealed interface LoginResult {
        record Success(AppUser user) implements LoginResult { }

        /** Unknown email or wrong password: callers must not tell these apart. */
        record BadCredentials(String email, Long userId, boolean unknownUser) implements LoginResult { }

        /** Refused because the account is locked; {@code newlyLocked} is true for the attempt that locked it. */
        record Locked(AppUser user, Instant until, boolean newlyLocked) implements LoginResult { }
    }

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final LoginPolicy policy = new LoginPolicy();
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
        // Compared against for unknown emails, so their response takes as long as a wrong password.
        this.dummyHash = passwords.encode("not-a-real-password-" + System.nanoTime());
    }

    public static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public LoginResult login(String rawEmail, String password) {
        String email = normalise(rawEmail);
        var found = users.findForLoginByEmail(email);
        if (found.isEmpty()) {
            passwords.matches(password, dummyHash);
            return new LoginResult.BadCredentials(email, null, true);
        }
        AppUser user = found.get();
        Instant now = clock.instant();
        LoginPolicy.State before = user.loginState();
        // During a lock the password is not even checked: the answer is the same either way.
        boolean matches = !before.lockedAt(now) && passwords.matches(password, user.getPasswordHash());
        LoginPolicy.Decision decision = policy.decide(before, matches, now);
        user.applyLoginState(decision.next());
        return switch (decision.outcome()) {
            case SUCCESS -> new LoginResult.Success(user);
            case BAD_CREDENTIALS -> new LoginResult.BadCredentials(email, user.getId(), false);
            case LOCKED -> new LoginResult.Locked(user, before.lockedUntil(), false);
            case NEWLY_LOCKED -> new LoginResult.Locked(user, decision.next().lockedUntil(), true);
        };
    }
}

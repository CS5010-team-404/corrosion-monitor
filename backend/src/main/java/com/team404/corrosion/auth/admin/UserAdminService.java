package com.team404.corrosion.auth.admin;

import com.team404.corrosion.auth.AppUser;
import com.team404.corrosion.auth.AppUserRepository;
import com.team404.corrosion.auth.LoginAudit;
import com.team404.corrosion.auth.LoginPolicy;
import com.team404.corrosion.auth.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin > Users: list accounts, clear a lockout, change a role. Each change and its audit_outbox row commit in one
 * transaction (JdbcTemplate joins the JPA transaction), so neither exists without the other. A request that changes
 * nothing (unlocking an account that is not locked, setting the role it already has) writes no audit row.
 */
@Service
public class UserAdminService {

    public static final String ACCOUNT_UNLOCKED = "ACCOUNT_UNLOCKED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";

    /** The frontend's User type. Never carries the password hash or the failure count. */
    public record UserView(Long id, String username, String fullName, String email, Role role, boolean enabled,
                           Instant lockedUntil, Instant lastLoginAt) {
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(long id) {
            super("No user with id " + id + ".");
        }
    }

    public static class RoleChangeRefusedException extends RuntimeException {
        public RoleChangeRefusedException(String message) {
            super(message);
        }
    }

    private final AppUserRepository users;
    private final LoginAudit audit;
    private final Clock clock;

    public UserAdminService(AppUserRepository users, LoginAudit audit, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        Instant now = clock.instant();
        return users.findAllByOrderByNameAscIdAsc().stream().map(u -> view(u, now)).toList();
    }

    @Transactional
    public UserView unlock(long id, String actorEmail) {
        AppUser user = users.findForUpdateById(id).orElseThrow(() -> new UserNotFoundException(id));
        LoginPolicy.State before = user.loginState();
        if (!before.equals(LoginPolicy.State.CLEAN)) {
            user.applyLoginState(LoginPolicy.State.CLEAN);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("user", user.getEmail());
            details.put("failedLogins", before.failedLogins());
            details.put("lockedUntil", before.lockedUntil() == null ? null : before.lockedUntil().toString());
            audit.record(ACCOUNT_UNLOCKED, actorEmail, user.getId(), details);
        }
        return view(user, clock.instant());
    }

    @Transactional
    public UserView changeRole(long id, Role to, String actorEmail) {
        // Admin rows first, then the target, always in this order (see findForUpdateByRoleOrderById).
        List<AppUser> admins = users.findForUpdateByRoleOrderById(Role.ADMIN);
        AppUser user = users.findForUpdateById(id).orElseThrow(() -> new UserNotFoundException(id));
        Role from = user.getRole();
        if (from == to) {
            return view(user, clock.instant());
        }
        long otherEnabledAdmins = admins.stream().filter(a -> a.isEnabled() && !a.getId().equals(user.getId())).count();
        UserAdminRules.refuseRoleChange(actorEmail, user.getEmail(), from, to, otherEnabledAdmins).ifPresent(reason -> {
            throw new RoleChangeRefusedException(reason);
        });
        user.changeRole(to);
        audit.record(USER_ROLE_CHANGED, actorEmail, user.getId(),
                Map.of("user", user.getEmail(), "oldRole", from.name(), "newRole", to.name()));
        return view(user, clock.instant());
    }

    /** lockedUntil only while the lock is in force: an expired lock stays in the row until the next sign-in. */
    static UserView view(AppUser u, Instant now) {
        LoginPolicy.State state = u.loginState();
        return new UserView(u.getId(), u.getEmail(), u.getName(), u.getEmail(), u.getRole(), u.isEnabled(),
                state.lockedAt(now) ? state.lockedUntil() : null, u.getLastLoginAt());
    }
}

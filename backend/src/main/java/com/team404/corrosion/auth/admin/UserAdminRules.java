package com.team404.corrosion.auth.admin;

import com.team404.corrosion.auth.Role;
import java.util.Optional;

/**
 * Which role changes an administrator may make, free of Spring and the database. Taking the ADMIN role away is refused
 * when the administrator is taking it from themselves, or when nobody else enabled would still hold it.
 */
public final class UserAdminRules {

    public static final String SELF_DEMOTION =
            "You can't remove your own administrator role. Ask another administrator to change it.";
    public static final String LAST_ADMIN =
            "This is the last administrator. Make someone else an administrator before changing this role.";

    private UserAdminRules() {
    }

    /**
     * @param otherEnabledAdmins enabled ADMIN accounts other than the target
     * @return the reason the change is refused, or empty when it is allowed
     */
    public static Optional<String> refuseRoleChange(String actorEmail, String targetEmail, Role from, Role to,
                                                    long otherEnabledAdmins) {
        if (from != Role.ADMIN || to == Role.ADMIN) {
            return Optional.empty();
        }
        if (actorEmail != null && actorEmail.equalsIgnoreCase(targetEmail)) {
            return Optional.of(SELF_DEMOTION);
        }
        if (otherEnabledAdmins < 1) {
            return Optional.of(LAST_ADMIN);
        }
        return Optional.empty();
    }
}

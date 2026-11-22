package com.team404.corrosion.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.team404.corrosion.auth.Role;
import org.junit.jupiter.api.Test;

class UserAdminRulesTest {

    private static final String ME = "admin@corrosionwatch.app";
    private static final String OTHER = "second.admin@corrosionwatch.app";

    @Test
    void anAdminCannotDemoteThemselvesEvenWithOtherAdmins() {
        assertThat(UserAdminRules.refuseRoleChange(ME, ME, Role.ADMIN, Role.MANAGER, 3))
                .contains(UserAdminRules.SELF_DEMOTION);
        assertThat(UserAdminRules.refuseRoleChange(ME, "Admin@CorrosionWatch.app", Role.ADMIN, Role.TECHNICIAN, 3))
                .as("emails compare case-insensitively").contains(UserAdminRules.SELF_DEMOTION);
    }

    @Test
    void theLastEnabledAdminCannotBeDemoted() {
        assertThat(UserAdminRules.refuseRoleChange(ME, OTHER, Role.ADMIN, Role.OPERATOR, 0))
                .contains(UserAdminRules.LAST_ADMIN);
    }

    @Test
    void anotherAdminCanBeDemotedWhileOneRemains() {
        assertThat(UserAdminRules.refuseRoleChange(ME, OTHER, Role.ADMIN, Role.OPERATOR, 1)).isEmpty();
    }

    @Test
    void changesThatKeepOrGrantAdminAreAlwaysAllowed() {
        assertThat(UserAdminRules.refuseRoleChange(ME, ME, Role.ADMIN, Role.ADMIN, 0)).isEmpty();
        assertThat(UserAdminRules.refuseRoleChange(ME, OTHER, Role.TECHNICIAN, Role.ADMIN, 0)).isEmpty();
        assertThat(UserAdminRules.refuseRoleChange(ME, OTHER, Role.TECHNICIAN, Role.MANAGER, 0)).isEmpty();
    }
}

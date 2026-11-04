package com.team404.corrosion.auth;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one demo user per role on an empty app_user table, so the demo can sign in on day one. The password comes
 * from ECP_DEMO_PASSWORD only; without it nothing is created (there is no default password). Turn off with
 * ECP_DEMO_SEED=false. Names match the UI's mock mode.
 */
@Component
public class DemoUsers implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoUsers.class);

    //                                   email                          name                role
    private static final Object[][] USERS = {
        {"tech@corrosionwatch.app", "Tariq Technician", Role.TECHNICIAN},
        {"operator@corrosionwatch.app", "Olivia Operator", Role.OPERATOR},
        {"manager@corrosionwatch.app", "Morgan Manager", Role.MANAGER},
        {"admin@corrosionwatch.app", "Avery Admin", Role.ADMIN},
    };

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final boolean enabled;
    private final String password;

    public DemoUsers(AppUserRepository users, PasswordEncoder passwords, Clock clock,
                     @Value("${ecp.demo.seed:true}") boolean enabled,
                     @Value("${ecp.demo.password:}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled || users.count() > 0) {
            return;
        }
        if (password == null || password.isBlank()) {
            LOG.warn("app_user is empty and ECP_DEMO_PASSWORD is not set: no demo users created");
            return;
        }
        for (Object[] u : USERS) {
            users.save(new AppUser((String) u[1], (String) u[0], passwords.encode(password), (Role) u[2], clock.instant()));
        }
        LOG.info("Created {} demo users (password from ECP_DEMO_PASSWORD)", USERS.length);
    }
}

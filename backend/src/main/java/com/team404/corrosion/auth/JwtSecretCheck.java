package com.team404.corrosion.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start without a usable ECP_JWT_SECRET, before any bean (database, Flyway) is created, so a missing secret
 * is the first and only error in the log. Registered in META-INF/spring.factories.
 */
public class JwtSecretCheck implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        JwtKeys.fromSecret(environment.getProperty("ecp.security.jwt-secret"));
    }
}

package com.team404.corrosion;

import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL 8.4 (the version Aiven runs) with the production settings recorded in docs/DEPLOYMENT.md, so tests catch SQL
 * that would only fail in production: ANSI quoting, strict mode, required primary keys. Keep in step with
 * docker-compose.yml.
 */
public final class AivenMySql {

    public static final String SQL_MODE = "REAL_AS_FLOAT,PIPES_AS_CONCAT,ANSI_QUOTES,IGNORE_SPACE,ONLY_FULL_GROUP_BY,"
            + "ANSI,STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";

    private AivenMySql() {
    }

    public static MySQLContainer<?> container() {
        return new MySQLContainer<>("mysql:8.4").withCommand(
                "--sql-mode=" + SQL_MODE,
                "--sql-require-primary-key=ON",
                // Binary logging is on in MySQL 8.4; without this the app account cannot create the audit triggers.
                "--log-bin-trust-function-creators=1");
    }
}

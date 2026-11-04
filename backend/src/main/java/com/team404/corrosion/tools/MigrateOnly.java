package com.team404.corrosion.tools;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/**
 * Runs the backend's Flyway migrations once against DB_URL and prints the applied versions and the tables. No Spring
 * context, so no JWT secret or web server is needed. Used by scripts/check-aiven.sh through Spring Boot's
 * PropertiesLauncher. Settings match backend/src/main/resources/application.properties (baseline at version 0, so it
 * also works on a database where the platform migrated first).
 */
public final class MigrateOnly {

    private MigrateOnly() {
    }

    public static void main(String[] args) throws SQLException {
        run(env("DB_URL"), env("MYSQL_USER"), env("MYSQL_PASSWORD"), System.out);
    }

    public static void run(String url, String user, String password, PrintStream out) throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        int executed = flyway.migrate().migrationsExecuted;
        out.println();
        out.println("Migrations run now: " + executed);
        out.println("Applied versions (flyway_schema_history):");
        for (MigrationInfo m : flyway.info().applied()) {
            out.printf("  %-4s %-28s %-8s %s%n", m.getVersion(), m.getDescription(), m.getState(), m.getInstalledOn());
        }
        out.println("Tables:");
        for (String table : tables(url, user, password)) {
            out.println("  " + table);
        }
    }

    private static List<String> tables(String url, String user, String password) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, user, password);
             ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}

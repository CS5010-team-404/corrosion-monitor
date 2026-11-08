package com.team404.corrosion.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.team404.corrosion.AivenMySql;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MigrateOnlyTest {

    @Container
    static final MySQLContainer<?> MYSQL = AivenMySql.container();

    private String run() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MigrateOnly.run(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static final File MIGRATIONS = new File("src/main/resources/db/migration");

    private static List<String> migrationFiles() {
        File[] files = MIGRATIONS.listFiles((dir, name) -> name.matches("V\\d+__.+\\.sql"));
        assertThat(files).as("migration files in " + MIGRATIONS.getAbsolutePath()).isNotNull().isNotEmpty();
        return Arrays.stream(files).map(File::getName).sorted().toList();
    }

    @Test
    void migratesOnceThenReportsVersionsAndTablesWithoutThePassword() throws Exception {
        List<String> files = migrationFiles();
        String first = run();
        assertThat(first).contains("Migrations run now: " + files.size());
        // Every migration is listed by its description (V4__audit_chain.sql -> "audit chain"); Flyway prints
        // underscores as spaces.
        for (String file : files) {
            String description = file.replaceFirst("^V\\d+__", "").replaceFirst("\\.sql$", "").replace('_', ' ');
            assertThat(first).as("migration " + file).contains(description);
        }
        assertThat(first).contains("team schema", "agreed changes", "demo reference data", "audit chain",
                "app_user", "equipment", "audit_outbox", "audit_log", "audit_chain_head", "audit_verification",
                "audit_checkpoint", "flyway_schema_history");
        assertThat(first).doesNotContain(MYSQL.getPassword() + "\n");

        assertThat(run()).contains("Migrations run now: 0");
    }
}

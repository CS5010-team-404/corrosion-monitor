package com.team404.corrosion.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.team404.corrosion.AivenMySql;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

    @Test
    void migratesOnceThenReportsVersionsAndTablesWithoutThePassword() throws Exception {
        String first = run();
        assertThat(first).contains("Migrations run now: 3", "team schema", "agreed changes", "demo reference data",
                "app_user", "equipment", "audit_outbox", "flyway_schema_history");
        assertThat(first).doesNotContain(MYSQL.getPassword() + "\n");

        assertThat(run()).contains("Migrations run now: 0");
    }
}

package com.team404.corrosion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.classpath.ClassPathResource;
import org.junit.jupiter.api.Test;

/**
 * Guards the migrations already applied in production. Flyway refuses to start when an applied migration's checksum
 * changes (comments count too), so an edited V1-V4 takes the deployed backend down. The expected checksums are in
 * src/test/resources/db/applied-migrations.txt and are computed here with Flyway's own calculator. New migrations
 * are allowed; changed, renamed or deleted listed ones are not.
 */
class MigrationChecksumTest {

    private static final String MIGRATIONS = "db/migration";
    private static final String APPLIED = "db/applied-migrations.txt";

    record Applied(int version, int checksum, String file) {
    }

    @Test
    void appliedMigrationsAreUnchanged() throws IOException {
        List<Applied> applied = applied();
        assertThat(applied).as(APPLIED + " lists the applied migrations").isNotEmpty();
        for (Applied a : applied) {
            String message = "V" + a.version() + " was changed after it was applied in production. "
                    + "Restore it and put the change in a new migration.";
            ClassPathResource resource = new ClassPathResource(new Location("classpath:" + MIGRATIONS),
                    MIGRATIONS + "/" + a.file(), getClass().getClassLoader(), StandardCharsets.UTF_8);
            assertThat(resource.exists()).as(message + " (" + a.file() + " is missing)").isTrue();
            assertThat(ChecksumCalculator.calculate(resource)).as(message + " (" + a.file() + ")")
                    .isEqualTo(a.checksum());
        }
    }

    private static List<Applied> applied() throws IOException {
        try (InputStream in = MigrationChecksumTest.class.getClassLoader().getResourceAsStream(APPLIED)) {
            assertThat(in).as(APPLIED + " on the test classpath").isNotNull();
            List<Applied> result = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                String[] f = l.split("\\s+");
                result.add(new Applied(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f[2]));
            }
            return result;
        }
    }
}

package com.team404.corrosion.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Keeps the cross-service token fixture honest. The fixture holds tokens issued by this backend's TokenService; the
 * platform's BackendTokenCompatibilityTest feeds the same file to the platform's real SecurityConfig. If the backend
 * changes its token format, this test fails until the fixture is regenerated (and the platform test then shows
 * whether the platform still accepts it):
 *
 * <pre>mvn -pl backend -am test -Dtest=TokenFixtureTest -DregenerateTokenFixture=true -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
class TokenFixtureTest {

    /** Obviously fake; lives only here and in the platform test. Never use it in configuration. */
    static final String FIXTURE_SECRET = "test-only-secret-never-use-in-production-0123";

    /** Far in the future, so the fixture tokens do not expire. */
    private static final Clock ISSUED_AT = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final Path BACKEND_COPY = Path.of("src/test/resources/fixtures/backend-jwt.txt");
    private static final Path PLATFORM_COPY =
            Path.of("../platform/ecp-platform-app/src/test/resources/fixtures/backend-jwt.txt");

    private static final JwtConfig CONFIG = new JwtConfig(FIXTURE_SECRET);
    private final JwtDecoder decoder = CONFIG.jwtDecoder();

    private static Map<String, String> issueFixtureTokens() {
        TokenService tokens = new TokenService(CONFIG.jwtEncoder(), ISSUED_AT);
        Map<String, String> t = new LinkedHashMap<>();
        t.put("TECHNICIAN", tokens.issue("tech@corrosionwatch.app", "Tariq Technician", Role.TECHNICIAN).value());
        t.put("ADMIN", tokens.issue("admin@corrosionwatch.app", "Avery Admin", Role.ADMIN).value());
        return t;
    }

    @BeforeAll
    static void regenerateWhenAsked() throws IOException {
        if (!Boolean.getBoolean("regenerateTokenFixture")) {
            return;
        }
        StringBuilder out = new StringBuilder("""
                # Tokens issued by the backend's TokenService (backend TokenFixtureTest) with the test-only secret in
                # that test and a clock fixed at 2099-01-01T00:00:00Z. Read by the platform's
                # BackendTokenCompatibilityTest. Regenerate with -DregenerateTokenFixture=true; keep both copies identical.
                """);
        issueFixtureTokens().forEach((role, token) -> out.append(role).append('=').append(token).append('\n'));
        for (Path p : new Path[] {BACKEND_COPY, PLATFORM_COPY}) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, out.toString(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> read(Path file) throws IOException {
        Map<String, String> t = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                int eq = line.indexOf('=');
                t.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return t;
    }

    @Test
    void fixtureHoldsTokensThisBackendIssuesToday() throws IOException {
        Map<String, String> fixture = read(BACKEND_COPY);
        Map<String, String> fresh = issueFixtureTokens();
        assertThat(fixture.keySet()).containsExactlyElementsOf(fresh.keySet());
        for (String role : fresh.keySet()) {
            Jwt fromFixture = decoder.decode(fixture.get(role));
            Jwt issuedNow = decoder.decode(fresh.get(role));
            assertThat(fromFixture.getHeaders()).isEqualTo(issuedNow.getHeaders());
            assertThat(fromFixture.getClaims()).isEqualTo(issuedNow.getClaims());
            assertThat(CONFIG.jwtAuthenticationConverter("roles").convert(fromFixture).getAuthorities())
                    .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_" + role);
        }
    }

    @Test
    void platformCopyIsIdentical() throws IOException {
        assertThat(PLATFORM_COPY).exists();
        assertThat(Files.readAllBytes(PLATFORM_COPY)).isEqualTo(Files.readAllBytes(BACKEND_COPY));
    }
}

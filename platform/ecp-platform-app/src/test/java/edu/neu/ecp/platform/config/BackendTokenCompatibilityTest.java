package edu.neu.ecp.platform.config;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tokens issued by the team backend are accepted by this platform's real SecurityConfig, and their roles map to the
 * same authorities. The tokens come from src/test/resources/fixtures/backend-jwt.txt, a byte-for-byte copy of the
 * backend's fixture that the backend's TokenFixtureTest checks against its TokenService.
 */
@WebMvcTest(controllers = BackendTokenCompatibilityTest.Probe.class, properties = {
        "ecp.platform.auth=jwt",
        "ecp.security.jwt-secret=" + BackendTokenCompatibilityTest.FIXTURE_SECRET})
@Import({SecurityConfig.class, CorsConfig.class, BackendTokenCompatibilityTest.Probe.class})
class BackendTokenCompatibilityTest {

    /** Same obviously fake secret as the backend's TokenFixtureTest. Never use it in configuration. */
    static final String FIXTURE_SECRET = "test-only-secret-never-use-in-production-0123";

    private static Map<String, String> tokens;

    @Autowired
    MockMvc mvc;

    /** Echoes what Spring Security made of the token. */
    @RestController
    static class Probe {
        @GetMapping("/api/v1/probe/me")
        Map<String, Object> me(Authentication auth) {
            return Map.of("name", auth.getName(),
                    "authorities", auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
        }

        @GetMapping("/api/v1/admin/probe")
        String admin() {
            return "ok";
        }
    }

    @BeforeAll
    static void readFixture() throws IOException {
        tokens = new LinkedHashMap<>();
        try (InputStream in = BackendTokenCompatibilityTest.class.getResourceAsStream("/fixtures/backend-jwt.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    tokens.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1).trim());
                }
            }
        }
    }

    private static String bearer(String role) {
        return "Bearer " + tokens.get(role);
    }

    @Test
    void technicianTokenFromTheBackendIsAcceptedWithTheSameRole() throws Exception {
        mvc.perform(get("/api/v1/probe/me").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("tech@corrosionwatch.app"))
                .andExpect(jsonPath("$.authorities", contains("ROLE_TECHNICIAN")));
    }

    @Test
    void adminPathsFollowTheBackendRole() throws Exception {
        mvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer("TECHNICIAN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/probe").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/probe/me").header("Authorization", bearer("ADMIN")))
                .andExpect(jsonPath("$.authorities", contains("ROLE_ADMIN")));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        byte[] other = "another-test-only-secret-never-in-production-01".getBytes(StandardCharsets.UTF_8);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<SecurityContext>(new SecretKeySpec(other, "HmacSHA256")));
        String forged = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("admin@corrosionwatch.app").issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600)).claim("roles", List.of("ADMIN")).build()))
                .getTokenValue();
        mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/probe/me")).andExpect(status().isUnauthorized());
    }
}

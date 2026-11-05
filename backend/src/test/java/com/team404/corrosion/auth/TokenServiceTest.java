package com.team404.corrosion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class TokenServiceTest {

    private static final String SECRET = "test-only-jwt-secret-for-backend-tests-0001";
    private static final String OTHER_SECRET = "test-only-jwt-secret-for-backend-tests-0002";

    private final MutableClock clock = new MutableClock(Instant.now());
    private final JwtConfig config = new JwtConfig(SECRET);
    private final TokenService tokens = new TokenService(config.jwtEncoder(), clock);
    private final JwtDecoder decoder = config.jwtDecoder();

    @Test
    void tokenCarriesEmailRoleNameAndAnEightHourLifetime() {
        TokenService.IssuedToken t = tokens.issue("tech@corrosionwatch.app", "Tariq Technician", Role.TECHNICIAN);
        Jwt jwt = decoder.decode(t.value());

        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getSubject()).isEqualTo("tech@corrosionwatch.app");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("TECHNICIAN");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Tariq Technician");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(8));
        assertThat(jwt.getExpiresAt()).isEqualTo(t.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void rolesBecomeSpringAuthoritiesWithRolePrefix() {
        Jwt jwt = decoder.decode(tokens.issue("a@corrosionwatch.app", "Avery Admin", Role.ADMIN).value());
        assertThat(config.jwtAuthenticationConverter("roles").convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void expiredTokenIsRejected() {
        clock.advance(Duration.ofHours(-9));
        String old = tokens.issue("tech@corrosionwatch.app", "Tariq Technician", Role.TECHNICIAN).value();
        assertThatThrownBy(() -> decoder.decode(old)).isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String forged = new TokenService(new JwtConfig(OTHER_SECRET).jwtEncoder(), clock)
                .issue("admin@corrosionwatch.app", "Mallory", Role.ADMIN).value();
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = tokens.issue("tech@corrosionwatch.app", "Tariq Technician", Role.TECHNICIAN).value().split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String elevated = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace("TECHNICIAN", "ADMIN").getBytes());
        assertThatThrownBy(() -> decoder.decode(parts[0] + "." + elevated + "." + parts[2]))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void secretShorterThan32BytesIsRefused() {
        assertThatThrownBy(() -> new JwtConfig("too-short")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ECP_JWT_SECRET");
        assertThatThrownBy(() -> new JwtConfig("")).isInstanceOf(IllegalStateException.class);
    }
}

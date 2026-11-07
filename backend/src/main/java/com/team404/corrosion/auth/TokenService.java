package com.team404.corrosion.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the JWT that both the backend and the platform accept (docs/CONTRACTS.md): HS256,
 * sub = email, roles = [ROLE], name = full name, 8-hour lifetime.
 */
@Service
public class TokenService {

    public static final Duration LIFETIME = Duration.ofHours(8);
    public static final String ROLES_CLAIM = "roles";
    public static final String NAME_CLAIM = "name";

    public record IssuedToken(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    public IssuedToken issue(AppUser user) {
        return issue(user.getEmail(), user.getName(), user.getRole());
    }

    public IssuedToken issue(String email, String fullName, Role role) {
        Instant now = clock.instant();
        Instant expires = now.plus(LIFETIME);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email)
                .issuedAt(now)
                .expiresAt(expires)
                .claim(ROLES_CLAIM, List.of(role.name()))
                .claim(NAME_CLAIM, fullName)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(value, expires);
    }
}

package com.team404.corrosion.auth;

import com.team404.corrosion.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in for the UI (docs/CONTRACTS.md). The token also works on the platform. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Same text for an unknown email and a wrong password, so the response does not reveal which emails exist. */
    public static final String BAD_CREDENTIALS = "Incorrect email or password.";

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, String email, String fullName, Role role) {
    }

    public record Me(String email, String fullName, String role) {
    }

    private final AuthService auth;
    private final TokenService tokens;
    private final ApiErrors errors;

    public AuthController(AuthService auth, TokenService tokens, ApiErrors errors) {
        this.auth = auth;
        this.tokens = tokens;
        this.errors = errors;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request.email(), request.password());
        if (result instanceof AuthService.LoginResult.Success s) {
            AppUser user = s.user();
            TokenService.IssuedToken token = tokens.issue(user);
            return ResponseEntity.ok(new LoginResponse(token.value(), token.expiresAt(), user.getEmail(), user.getName(),
                    user.getRole()));
        }
        if (result instanceof AuthService.LoginResult.Locked l) {
            String message = "Account locked after " + LoginPolicy.MAX_FAILURES
                    + " failed sign-ins. Try again after " + l.until() + " (UTC).";
            return errors.response(HttpStatus.LOCKED, "ACCOUNT_LOCKED", message, Map.of("lockedUntil", l.until().toString()));
        }
        return errors.response(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", BAD_CREDENTIALS, Map.of());
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(TokenService.ROLES_CLAIM);
        return new Me(jwt.getSubject(), jwt.getClaimAsString(TokenService.NAME_CLAIM),
                roles == null || roles.isEmpty() ? null : roles.get(0));
    }
}

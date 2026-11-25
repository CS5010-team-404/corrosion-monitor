package com.team404.corrosion.profile;

import com.team404.corrosion.audit.AuditService;
import com.team404.corrosion.web.ApiErrors;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Equipment profiles (FR8). GET: any signed-in user (SecurityConfig requires a token on /api/v1/**). PUT: admins only,
 * checked here from the token's roles claim; a refused PUT is recorded as ACCESS_DENIED like the admin-only audit
 * endpoints.
 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private static final String ROLES_CLAIM = "roles";

    private final ProfileService profiles;
    private final AuditService audit;
    private final ApiErrors errors;

    public ProfileController(ProfileService profiles, AuditService audit, ApiErrors errors) {
        this.profiles = profiles;
        this.audit = audit;
        this.errors = errors;
    }

    @GetMapping
    public List<Profile> list() {
        return profiles.list();
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id,
                                    @RequestBody ProfileUpdate body) {
        List<String> roles = jwt == null ? null : jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null || !roles.contains("ADMIN")) {
            audit.recordDenied(jwt == null ? null : jwt.getSubject(), "PUT /api/v1/profiles/" + id);
            return errors.response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Administrator access required.", Map.of());
        }
        try {
            return ResponseEntity.ok(profiles.update(id, body, jwt.getSubject()));
        } catch (ProfileService.NotFound e) {
            return errors.response(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), Map.of());
        } catch (ProfileService.Invalid e) {
            return errors.response(HttpStatus.BAD_REQUEST, "INVALID_PROFILE", e.getMessage(),
                    Map.of("problems", e.problems()));
        } catch (ProfileService.NameTaken | DuplicateKeyException e) {
            String message = e instanceof ProfileService.NameTaken ? e.getMessage()
                    : "Another profile already has that name.";
            return errors.response(HttpStatus.CONFLICT, "NAME_TAKEN", message, Map.of());
        }
    }
}

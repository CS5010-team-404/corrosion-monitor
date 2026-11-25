package com.team404.corrosion.auth.admin;

import com.team404.corrosion.auth.Role;
import com.team404.corrosion.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin > Users (docs/CONTRACTS.md). Admins only, through the /api/v1/admin/** rule in SecurityConfig. The acting
 * admin is the token's subject (their email), which is what the audit rows record as the actor.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserAdminController {

    public record RoleChange(@NotBlank String role) {
    }

    private static final String ROLES = Arrays.stream(Role.values()).map(Role::name).collect(Collectors.joining(", "));

    private final UserAdminService service;
    private final ApiErrors errors;

    public UserAdminController(UserAdminService service, ApiErrors errors) {
        this.service = service;
        this.errors = errors;
    }

    @GetMapping
    public List<UserAdminService.UserView> list() {
        return service.list();
    }

    @PostMapping("/{id}/unlock")
    public UserAdminService.UserView unlock(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        return service.unlock(id, jwt.getSubject());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> changeRole(@PathVariable("id") long id, @Valid @RequestBody RoleChange body,
                                        @AuthenticationPrincipal Jwt jwt) {
        Role role;
        try {
            role = Role.valueOf(body.role().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return errors.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "role must be one of " + ROLES + ".",
                    Map.of());
        }
        return ResponseEntity.ok(service.changeRole(id, role, jwt.getSubject()));
    }

    @ExceptionHandler(UserAdminService.UserNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(UserAdminService.UserNotFoundException e) {
        return errors.response(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), Map.of());
    }

    @ExceptionHandler(UserAdminService.RoleChangeRefusedException.class)
    ResponseEntity<Map<String, Object>> refused(UserAdminService.RoleChangeRefusedException e) {
        return errors.response(HttpStatus.CONFLICT, "ROLE_CHANGE_REFUSED", e.getMessage(), Map.of());
    }
}

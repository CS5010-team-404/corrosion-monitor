package com.team404.corrosion.audit;

import com.team404.corrosion.web.ApiErrors;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * View of the audit log (list: managers and admins; verify and checkpoints: admins only), with the paths and field names the dashboard expects (docs/GAPS.md). The role is
 * checked here on the server from the token's roles claim, so hiding a button in the UI is never the only protection.
 * Refused requests are themselves recorded (ACCESS_DENIED).
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    /** Same claim name the sign-in service puts in the token. */
    private static final String ROLES_CLAIM = "roles";

    private final AuditService audit;
    private final ApiErrors errors;

    public AuditController(AuditService audit, ApiErrors errors) {
        this.audit = audit;
        this.errors = errors;
    }

    /**
     * Newest first, as a JSON array. All filters are optional. from is inclusive and to is exclusive (ISO-8601
     * instants). page starts at 0 and size is capped at 200.
     */
    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(name = "actor", required = false) String actor,
                                  @RequestParam(name = "action", required = false) String action,
                                  @RequestParam(name = "entityType", required = false) String entityType,
                                  @RequestParam(name = "from", required = false) Instant from,
                                  @RequestParam(name = "to", required = false) Instant to,
                                  @RequestParam(name = "page", defaultValue = "0") int page,
                                  @RequestParam(name = "size", defaultValue = "50") int size) {
        ResponseEntity<?> denied = requireRole(jwt, "GET /api/v1/audit", false);
        if (denied != null) {
            return denied;
        }
        AuditStore.Filter filter = new AuditStore.Filter(blankToNull(actor), blankToNull(action),
                blankToNull(entityType), toLocal(from), toLocal(to));
        List<AuditViews.EntryView> entries = audit.search(filter, page, size).items().stream()
                .map(AuditViews::entry)
                .toList();
        return ResponseEntity.ok(entries);
    }

    /** Walks the whole chain and returns {intact, checked, firstBrokenId, message}. Also stored, see /verifications. */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<?> denied = requireAdmin(jwt, "POST /api/v1/audit/verify");
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(AuditViews.verify(audit.verify(jwt.getSubject())));
    }

    @GetMapping("/verifications")
    public ResponseEntity<?> verifications(@AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<?> denied = requireAdmin(jwt, "GET /api/v1/audit/verifications");
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(audit.recentVerifications());
    }

    @GetMapping("/checkpoints")
    public ResponseEntity<?> checkpoints(@AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<?> denied = requireAdmin(jwt, "GET /api/v1/audit/checkpoints");
        if (denied != null) {
            return denied;
        }
        return ResponseEntity.ok(audit.recentCheckpoints());
    }

    /** Saves a checkpoint now (one is also saved every night). */
    @PostMapping("/checkpoints")
    public ResponseEntity<?> saveCheckpoint(@AuthenticationPrincipal Jwt jwt) {
        ResponseEntity<?> denied = requireAdmin(jwt, "POST /api/v1/audit/checkpoints");
        if (denied != null) {
            return denied;
        }
        audit.saveCheckpoint();
        return ResponseEntity.status(HttpStatus.CREATED).body(audit.recentCheckpoints());
    }

    /** Returns null when the caller is an administrator, otherwise the error response to send. */
    private ResponseEntity<?> requireAdmin(Jwt jwt, String endpoint) {
        return requireRole(jwt, endpoint, true);
    }

    /** adminOnly false also lets MANAGER through (the audit list); verify, checkpoints stay admin-only. */
    private ResponseEntity<?> requireRole(Jwt jwt, String endpoint, boolean adminOnly) {
        if (jwt == null) {
            return errors.response(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required.", Map.of());
        }
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (adminOnly ? AuditViews.isAdmin(roles) : AuditViews.canViewLog(roles)) {
            return null;
        }
        audit.recordDenied(jwt.getSubject(), endpoint);
        return errors.response(HttpStatus.FORBIDDEN, "FORBIDDEN", adminOnly ? "Administrator access required." : "Manager or administrator access required.", Map.of());
    }

    private static LocalDateTime toLocal(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

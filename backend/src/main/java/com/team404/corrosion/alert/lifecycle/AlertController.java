package com.team404.corrosion.alert.lifecycle;

import com.team404.corrosion.alert.lifecycle.AlertViews.ActionView;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertDetail;
import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import com.team404.corrosion.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/alerts (docs/CONTRACTS.md). Everyone signed in reads; SecurityConfig lets only TECHNICIAN and ADMIN POST.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    public record ResolveRequest(@Size(max = 1000) String notes) {
    }

    public record ActionRequest(@NotBlank @Size(max = 40) String actionType, @Size(max = 1000) String notes) {
    }

    private final AlertService service;
    private final ApiErrors errors;

    public AlertController(AlertService service, ApiErrors errors) {
        this.service = service;
        this.errors = errors;
    }

    @GetMapping
    public List<AlertView> list(@RequestParam(name = "status", required = false) String status,
                                @RequestParam(name = "limit", required = false) Integer limit) {
        return service.list(status, limit);
    }

    @GetMapping("/{id}")
    public AlertDetail detail(@PathVariable("id") long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/acknowledge")
    public AlertView acknowledge(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        return service.acknowledge(id, jwt.getSubject());
    }

    @PostMapping("/{id}/resolve")
    public AlertView resolve(@PathVariable("id") long id, @Valid @RequestBody(required = false) ResolveRequest body,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.resolve(id, jwt.getSubject(), body == null ? null : body.notes());
    }

    @PostMapping("/{id}/actions")
    public ResponseEntity<ActionView> addAction(@PathVariable("id") long id, @Valid @RequestBody ActionRequest body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addAction(id, jwt.getSubject(), body.actionType(), body.notes()));
    }

    @ExceptionHandler(AlertException.class)
    ResponseEntity<Map<String, Object>> refused(AlertException e) {
        return errors.response(e.status(), e.code(), e.getMessage(), Map.of());
    }
}

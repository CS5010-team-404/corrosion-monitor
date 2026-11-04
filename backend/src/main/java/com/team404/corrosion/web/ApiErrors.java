package com.team404.corrosion.web;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The team's error format (docs/1-api-contract.md): {timestamp, status, error, message}, where error is a short code.
 * The frontend shows {@code message} to the user.
 */
@Component
public class ApiErrors {

    private final Clock clock;

    public ApiErrors(Clock clock) {
        this.clock = clock;
    }

    public Map<String, Object> body(HttpStatus status, String code, String message, Map<String, ?> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", clock.instant().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        body.putAll(extra);
        return body;
    }

    public ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message,
                                                        Map<String, ?> extra) {
        return ResponseEntity.status(status).body(body(status, code, message, extra));
    }
}

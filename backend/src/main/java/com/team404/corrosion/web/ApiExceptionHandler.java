package com.team404.corrosion.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns malformed requests into the team's error format instead of Spring's default body. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final ApiErrors errors;

    public ApiExceptionHandler(ApiErrors errors) {
        this.errors = errors;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String fields = String.join(", ", e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).sorted()
                .distinct().toList());
        return errors.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Missing or invalid: " + fields + ".", Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return errors.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request body is not valid JSON.", Map.of());
    }
}

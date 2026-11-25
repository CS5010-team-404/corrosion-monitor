package com.team404.corrosion.alert.lifecycle;

import org.springframework.http.HttpStatus;

/** A request the alert service refuses; the controller turns it into the team's error format. */
public class AlertException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AlertException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AlertException notFound(long id) {
        return new AlertException(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", "Alert " + id + " does not exist.");
    }

    public static AlertException wrongState(long id, AlertStatus current, String wanted) {
        return new AlertException(HttpStatus.CONFLICT, "INVALID_STATE",
                "Alert " + id + " is " + current + " and cannot be " + wanted + ".");
    }
}

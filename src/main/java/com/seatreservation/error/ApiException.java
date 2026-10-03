package com.seatreservation.error;

import org.springframework.http.HttpStatus;

/** A domain outcome that maps to a 4xx response. Stackless: declines are expected and frequent under load. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    protected ApiException(HttpStatus status, String reason, String message) {
        super(message, null, false, false);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}

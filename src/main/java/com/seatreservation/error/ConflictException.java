package com.seatreservation.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String reason, String message) {
        super(HttpStatus.CONFLICT, reason, message);
    }
}

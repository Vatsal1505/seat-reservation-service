package com.seatreservation.error;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String reason, String message) {
        super(HttpStatus.BAD_REQUEST, reason, message);
    }
}

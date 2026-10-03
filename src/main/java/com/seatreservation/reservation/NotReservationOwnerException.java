package com.seatreservation.reservation;

import com.seatreservation.error.ApiException;
import org.springframework.http.HttpStatus;

public class NotReservationOwnerException extends ApiException {

    public NotReservationOwnerException() {
        super(HttpStatus.FORBIDDEN, "NOT_RESERVATION_OWNER", "Only the user who made a reservation can change it.");
    }
}

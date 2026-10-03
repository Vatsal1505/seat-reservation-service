package com.seatreservation.reservation;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ReservationStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    @JsonValue
    String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

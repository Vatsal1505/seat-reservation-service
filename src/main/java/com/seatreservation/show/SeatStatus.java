package com.seatreservation.show;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED;

    @JsonValue
    String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package com.seatreservation.show;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record SeatId(
        @Column(name = "show_id") UUID showId,
        @Column(name = "seat_label") String seatLabel) implements Serializable {
}

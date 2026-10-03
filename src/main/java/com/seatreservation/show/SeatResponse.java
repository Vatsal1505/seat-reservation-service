package com.seatreservation.show;

public record SeatResponse(String seatLabel, SeatStatus status) {

    static SeatResponse of(Seat seat) {
        return new SeatResponse(seat.getSeatLabel(), seat.getStatus());
    }
}

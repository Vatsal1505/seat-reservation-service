package com.seatreservation.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID showId,
        List<String> seats,
        long totalPaise,
        ReservationStatus status,
        Instant expiresAt) {

    static ReservationResponse of(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getShowId(),
                reservation.getSeatLabels(),
                reservation.getTotalPaise(),
                reservation.getStatus(),
                reservation.getExpiresAt());
    }
}

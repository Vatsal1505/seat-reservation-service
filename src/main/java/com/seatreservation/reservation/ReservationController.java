package com.seatreservation.reservation;

import com.seatreservation.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{showId}/reserve")
    @PreAuthorize("hasRole('USER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@PathVariable UUID showId, @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ReserveRequest request) {
        Reservation reservation = reservationService.reserve(
                showId, user.userId(), request.distinctSeatLabels(), request.idempotencyKey());
        return ReservationResponse.of(reservation);
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ReservationResponse cancel(@PathVariable UUID reservationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ReservationResponse.of(reservationService.cancel(reservationId, user.userId()));
    }
}

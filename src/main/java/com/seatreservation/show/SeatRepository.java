package com.seatreservation.show;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {

    List<Seat> findByIdShowId(UUID showId);

    /** Seats with an owner are held or confirmed; the schema forbids an owner on an available seat. */
    long countByIdShowIdAndUserId(UUID showId, String userId);
}

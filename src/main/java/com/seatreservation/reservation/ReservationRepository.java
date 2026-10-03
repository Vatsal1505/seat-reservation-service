package com.seatreservation.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByShowIdAndUserIdAndIdempotencyKey(UUID showId, String userId, String idempotencyKey);

    // The status is a literal, not a parameter, so the partial index on held reservations stays usable.
    @Query("select r.id from Reservation r "
            + "where r.status = com.seatreservation.reservation.ReservationStatus.HELD and r.expiresAt <= :now "
            + "order by r.expiresAt")
    List<UUID> findExpiredHeldIds(@Param("now") Instant now);
}

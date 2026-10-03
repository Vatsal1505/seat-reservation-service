package com.seatreservation.reservation;

import com.seatreservation.show.Seat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The only place seats are locked, so every code path shares one lock order. */
@Component
public class SeatLocker {

    private final EntityManager entityManager;

    public SeatLocker(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Serialises one user's requests for one show; released when the transaction ends.
     * Always take it before any seat lock, never after, so the lock order stays user then seats.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUserInShow(UUID showId, String userId) {
        // Cast to text because the function returns void, which JDBC cannot read.
        entityManager.createNativeQuery("select cast(pg_advisory_xact_lock(hashtextextended(:key, 0)) as text)")
                .setParameter("key", showId + ":" + userId)
                .getSingleResult();
    }

    /** Reservation rows are locked before their seats by every path that takes both (cancel, expiry). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Reservation> lockReservation(UUID reservationId) {
        return Optional.ofNullable(
                entityManager.find(Reservation.class, reservationId, LockModeType.PESSIMISTIC_WRITE));
    }

    /** Locks the matching seats in seat-label order and returns them in that order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Seat> lockSeats(UUID showId, Collection<String> seatLabels) {
        // The explicit ORDER BY is what makes the lock order deterministic; never rely on scan order.
        return entityManager.createQuery("select s from Seat s where s.id.showId = :showId "
                        + "and s.id.seatLabel in :seatLabels order by s.id.seatLabel", Seat.class)
                .setParameter("showId", showId)
                .setParameter("seatLabels", seatLabels)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
    }
}

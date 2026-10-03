package com.seatreservation.reservation;

import com.seatreservation.error.ConflictException;
import com.seatreservation.error.InvalidRequestException;
import com.seatreservation.show.Seat;
import com.seatreservation.show.SeatRepository;
import com.seatreservation.show.SeatStatus;
import com.seatreservation.show.Show;
import com.seatreservation.show.ShowNotFoundException;
import com.seatreservation.show.ShowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final SeatLocker seatLocker;
    private final ReservationProperties properties;
    private final Clock clock;

    public ReservationService(ShowRepository shows, SeatRepository seats, ReservationRepository reservations,
            SeatLocker seatLocker, ReservationProperties properties, Clock clock) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.seatLocker = seatLocker;
        this.properties = properties;
        this.clock = clock;
    }

    /** All-or-nothing: every decision is made before the first write, so a declined request writes nothing. */
    @Transactional
    public Reservation reserve(UUID showId, String userId, Set<String> requestedLabels, String idempotencyKey) {
        Show show = shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        Set<String> labels = new TreeSet<>(requestedLabels);

        seatLocker.lockUserInShow(showId, userId);

        var replay = reservations.findByShowIdAndUserIdAndIdempotencyKey(showId, userId, idempotencyKey);
        if (replay.isPresent()) {
            return replayOf(replay.get(), labels);
        }

        long alreadyHeld = seats.countByIdShowIdAndUserId(showId, userId);
        List<Seat> locked = seatLocker.lockSeats(showId, labels);

        rejectUnknownSeats(labels, locked);
        rejectUnavailableSeats(locked);
        if (alreadyHeld + labels.size() > properties.maxSeatsPerUser()) {
            throw new ConflictException("SEAT_LIMIT_EXCEEDED", "A user can hold at most "
                    + properties.maxSeatsPerUser() + " seats per show; " + alreadyHeld + " already held.");
        }

        Instant now = Instant.now(clock);
        Reservation reservation = reservations.save(Reservation.held(showId, userId, idempotencyKey,
                List.copyOf(labels), show.getPricePaise() * labels.size(),
                now.plus(properties.holdDuration()), now));
        locked.forEach(seat -> seat.hold(reservation.getId(), userId, now));

        log.info("seats reserved. reservationId={}, showId={}, seatCount={}",
                reservation.getId(), showId, labels.size());
        return reservation;
    }

    /** Reservation row first, then its seats in sorted order; the expiry sweep locks in the same order. */
    @Transactional
    public Reservation cancel(UUID reservationId, String userId) {
        Reservation reservation = seatLocker.lockReservation(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!reservation.getUserId().equals(userId)) {
            throw new NotReservationOwnerException();
        }
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.EXPIRED) {
            throw new ConflictException("RESERVATION_EXPIRED", "This reservation has already expired.");
        }

        Instant now = Instant.now(clock);
        releaseSeats(reservation, now);
        reservation.markCancelled(now);

        log.info("reservation cancelled. reservationId={}, showId={}, seatCount={}",
                reservation.getId(), reservation.getShowId(), reservation.getSeatLabels().size());
        return reservation;
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredReservationIds() {
        return reservations.findExpiredHeldIds(Instant.now(clock));
    }

    /** Re-checks under the row lock, so a cancel or another instance that got there first makes this a no-op. */
    @Transactional
    public boolean expire(UUID reservationId) {
        Reservation reservation = seatLocker.lockReservation(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.HELD) {
            return false;
        }
        Instant now = Instant.now(clock);
        releaseSeats(reservation, now);
        reservation.markExpired(now);
        log.info("hold expired. reservationId={}, showId={}, seatCount={}",
                reservation.getId(), reservation.getShowId(), reservation.getSeatLabels().size());
        return true;
    }

    private void releaseSeats(Reservation reservation, Instant now) {
        seatLocker.lockSeats(reservation.getShowId(), reservation.getSeatLabels())
                .forEach(seat -> seat.release(now));
    }

    private static Reservation replayOf(Reservation original, Set<String> labels) {
        if (!Set.copyOf(original.getSeatLabels()).equals(labels)) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different set of seats.");
        }
        if (!original.isActive()) {
            throw new ConflictException("RESERVATION_NOT_ACTIVE",
                    "The reservation for this idempotency key was cancelled or has expired; use a new key.");
        }
        return original;
    }

    private static void rejectUnknownSeats(Set<String> labels, List<Seat> locked) {
        if (locked.size() == labels.size()) {
            return;
        }
        Set<String> unknown = new TreeSet<>(labels);
        locked.forEach(seat -> unknown.remove(seat.getSeatLabel()));
        throw new InvalidRequestException("UNKNOWN_SEATS", "Unknown seats for this show: " + unknown);
    }

    private static void rejectUnavailableSeats(List<Seat> locked) {
        List<String> unavailable = locked.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getSeatLabel)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new ConflictException("SEATS_UNAVAILABLE", "Seats not available: " + unavailable);
        }
    }
}

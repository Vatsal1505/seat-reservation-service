package com.seatreservation.show;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "seats")
public class Seat implements Persistable<SeatId> {

    @EmbeddedId
    private SeatId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Assigned ids make Spring Data treat save() as a merge (one SELECT per seat) unless the entity says it is new.
    @Transient
    private boolean newEntity = true;

    protected Seat() {
    }

    public static Seat available(UUID showId, String seatLabel, Instant now) {
        Seat seat = new Seat();
        seat.id = new SeatId(showId, seatLabel);
        seat.status = SeatStatus.AVAILABLE;
        seat.updatedAt = now;
        return seat;
    }

    public void hold(UUID reservationId, String userId, Instant now) {
        this.status = SeatStatus.HELD;
        this.reservationId = reservationId;
        this.userId = userId;
        this.updatedAt = now;
    }

    public void release(Instant now) {
        this.status = SeatStatus.AVAILABLE;
        this.reservationId = null;
        this.userId = null;
        this.updatedAt = now;
    }

    @Override
    public SeatId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    public String getSeatLabel() {
        return id.seatLabel();
    }

    public SeatStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Seat seat && Objects.equals(id, seat.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

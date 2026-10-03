package com.seatreservation.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "show_id", nullable = false, updatable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "seat_labels", nullable = false, updatable = false)
    private List<String> seatLabels;

    @Column(name = "total_paise", nullable = false, updatable = false)
    private long totalPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
    }

    public static Reservation held(UUID showId, String userId, String idempotencyKey, List<String> seatLabels,
            long totalPaise, Instant expiresAt, Instant now) {
        Reservation reservation = new Reservation();
        reservation.showId = showId;
        reservation.userId = userId;
        reservation.idempotencyKey = idempotencyKey;
        reservation.seatLabels = List.copyOf(seatLabels);
        reservation.totalPaise = totalPaise;
        reservation.status = ReservationStatus.HELD;
        reservation.expiresAt = expiresAt;
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getUserId() {
        return userId;
    }

    public List<String> getSeatLabels() {
        return seatLabels;
    }

    public long getTotalPaise() {
        return totalPaise;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return status == ReservationStatus.HELD;
    }

    public void markCancelled(Instant now) {
        this.status = ReservationStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void markExpired(Instant now) {
        this.status = ReservationStatus.EXPIRED;
        this.updatedAt = now;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Reservation reservation && id != null && Objects.equals(id, reservation.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

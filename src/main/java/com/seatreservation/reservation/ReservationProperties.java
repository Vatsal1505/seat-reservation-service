package com.seatreservation.reservation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(
        @NotNull Duration holdDuration,
        @Positive int maxSeatsPerUser,
        @NotNull Duration sweepInterval) {

    public ReservationProperties {
        requirePositive(holdDuration, "reservation.hold-duration");
        requirePositive(sweepInterval, "reservation.sweep-interval");
    }

    private static void requirePositive(Duration value, String property) {
        if (value != null && !value.isPositive()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}

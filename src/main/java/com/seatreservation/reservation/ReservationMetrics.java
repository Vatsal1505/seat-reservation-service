package com.seatreservation.reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void created() {
        count("created", "none");
    }

    public void declined(String reason) {
        count("declined", reason);
    }

    // Every series of one metric must carry the same tag keys, hence reason="none" on created.
    private void count(String outcome, String reason) {
        Counter.builder("reservations")
                .description("Reserve requests by outcome; created includes replays of an earlier request")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}

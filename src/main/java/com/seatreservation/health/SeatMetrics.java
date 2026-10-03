package com.seatreservation.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SeatMetrics {

    // Read through the monitoring pool so a scrape never competes with reservations for a connection.
    public SeatMetrics(MeterRegistry registry, MonitoringDatabase database) {
        Gauge.builder("seats.available", database, SeatMetrics::availableSeats)
                .description("Seats currently available across all shows")
                .register(registry);
    }

    private static double availableSeats(MonitoringDatabase database) {
        try {
            return database.countAvailableSeats();
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }
}

package com.seatreservation.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    private final MonitoringDatabase database;

    public DatabaseHealthIndicator(MonitoringDatabase database) {
        this.database = database;
    }

    @Override
    public Health health() {
        try {
            database.ping();
            return Health.up().build();
        } catch (RuntimeException ex) {
            return Health.down(ex).build();
        }
    }
}

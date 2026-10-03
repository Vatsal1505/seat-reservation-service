package com.seatreservation.health;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A tiny pool of its own, so a burst that exhausts the main pool cannot make health checks time out and get the
 * service restarted. Kept out of the DataSource type so Boot's auto-configured main pool stays in place.
 */
@Component
public class MonitoringDatabase implements AutoCloseable {

    private static final int MAX_CONNECTIONS = 2;
    private static final int TIMEOUT_MILLIS = 2_000;

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    // Connection details, not raw properties: they also cover connections supplied by the platform or by tests.
    public MonitoringDatabase(JdbcConnectionDetails connection) {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(connection.getJdbcUrl());
        dataSource.setUsername(connection.getUsername());
        dataSource.setPassword(connection.getPassword());
        dataSource.setPoolName("monitoring");
        dataSource.setMaximumPoolSize(MAX_CONNECTIONS);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(TIMEOUT_MILLIS);
        // Do not fail startup when the database is down; the health check is what reports that.
        dataSource.setInitializationFailTimeout(-1);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(TIMEOUT_MILLIS / 1_000);
    }

    /** Throws if the database cannot answer within the timeout. */
    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

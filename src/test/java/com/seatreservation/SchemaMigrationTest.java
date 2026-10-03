package com.seatreservation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SchemaMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE seats, reservations, shows CASCADE");
    }

    @Test
    void migrate_freshDatabase_createsAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' "
                        + "ORDER BY table_name",
                String.class);

        assertEquals(List.of("reservations", "seats", "shows"), tables);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void insertShow_nonPositivePrice_isRejected(long pricePaise) {
        assertRejectedBy("shows_price_paise_check", () -> insertShow(pricePaise));
    }

    @Test
    void insertSeat_availableWithoutOwner_isAccepted() {
        UUID showId = insertShow(25000);

        assertDoesNotThrow(() -> insertAvailableSeat(showId, "A1"));
    }

    @Test
    void insertSeat_heldWithReservationAndUser_isAccepted() {
        UUID showId = insertShow(25000);
        UUID reservationId = insertReservation(showId, "user-1", "key-1");

        assertDoesNotThrow(() -> insertSeat(showId, "A1", "HELD", reservationId, "user-1"));
    }

    @Test
    void insertSeat_availableWithOwner_isRejected() {
        UUID showId = insertShow(25000);
        UUID reservationId = insertReservation(showId, "user-1", "key-1");

        assertRejectedBy("ck_seats_owner",
                () -> insertSeat(showId, "A1", "AVAILABLE", reservationId, "user-1"));
    }

    @Test
    void insertSeat_heldWithoutReservationOrUser_isRejected() {
        UUID showId = insertShow(25000);

        assertRejectedBy("ck_seats_owner", () -> insertSeat(showId, "A1", "HELD", null, null));
    }

    @Test
    void insertSeat_unknownStatus_isRejected() {
        UUID showId = insertShow(25000);
        UUID reservationId = insertReservation(showId, "user-1", "key-1");

        assertRejectedBy("seats_status_check",
                () -> insertSeat(showId, "A1", "sold", reservationId, "user-1"));
    }

    @Test
    void insertSeat_duplicateLabelInSameShow_isRejected() {
        UUID showId = insertShow(25000);
        insertAvailableSeat(showId, "A1");

        assertRejectedBy("seats_pkey", () -> insertAvailableSeat(showId, "A1"));
    }

    @Test
    void insertSeat_sameLabelInDifferentShow_isAccepted() {
        UUID firstShow = insertShow(25000);
        UUID secondShow = insertShow(25000);
        insertAvailableSeat(firstShow, "A1");

        assertDoesNotThrow(() -> insertAvailableSeat(secondShow, "A1"));
    }

    @Test
    void insertReservation_unknownStatus_isRejected() {
        UUID showId = insertShow(25000);

        assertRejectedBy("reservations_status_check",
                () -> insertReservation(showId, "user-1", "key-1", "PENDING"));
    }

    @Test
    void insertReservation_duplicateKeyForSameShowAndUser_isRejected() {
        UUID showId = insertShow(25000);
        insertReservation(showId, "user-1", "key-1");

        assertRejectedBy("uq_reservations_idempotency",
                () -> insertReservation(showId, "user-1", "key-1"));
    }

    @Test
    void insertReservation_sameKeyForDifferentUser_isAccepted() {
        UUID showId = insertShow(25000);
        insertReservation(showId, "user-1", "key-1");

        assertDoesNotThrow(() -> insertReservation(showId, "user-2", "key-1"));
    }

    @Test
    void insertReservation_sameKeyOnDifferentShow_isAccepted() {
        UUID firstShow = insertShow(25000);
        UUID secondShow = insertShow(25000);
        insertReservation(firstShow, "user-1", "key-1");

        assertDoesNotThrow(() -> insertReservation(secondShow, "user-1", "key-1"));
    }

    private static void assertRejectedBy(String constraint, Executable insert) {
        var ex = assertThrows(DataIntegrityViolationException.class, insert);
        assertTrue(ex.getMessage().contains(constraint), ex.getMessage());
    }

    private static UUID insertShow(long pricePaise) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shows (id, name, price_paise) VALUES (?, 'friday-night', ?)", id, pricePaise);
        return id;
    }

    private static UUID insertReservation(UUID showId, String userId, String key) {
        return insertReservation(showId, userId, key, "HELD");
    }

    private static UUID insertReservation(UUID showId, String userId, String key, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reservations "
                        + "(id, show_id, user_id, idempotency_key, seat_labels, total_paise, status, expires_at) "
                        + "VALUES (?, ?, ?, ?, '{A1}'::text[], 25000, ?, now() + interval '5 minutes')",
                id, showId, userId, key, status);
        return id;
    }

    private static void insertAvailableSeat(UUID showId, String label) {
        insertSeat(showId, label, "AVAILABLE", null, null);
    }

    private static void insertSeat(UUID showId, String label, String status, UUID reservationId, String userId) {
        jdbc.update("INSERT INTO seats (show_id, seat_label, status, reservation_id, user_id) VALUES (?, ?, ?, ?, ?)",
                showId, label, status, reservationId, userId);
    }
}

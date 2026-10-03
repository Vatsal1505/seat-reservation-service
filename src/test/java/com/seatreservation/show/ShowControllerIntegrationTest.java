package com.seatreservation.show;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Testcontainers
class ShowControllerIntegrationTest {

    private static final String ADMIN = "Bearer admin:ops-1";
    private static final String USER = "Bearer user:alice";
    private static final String THREE_SEATS = """
            {"name": "friday-night", "seats": ["A1", "A2", "A3"], "price_paise": 25000}""";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final Statistics statistics;

    @Autowired
    ShowControllerIntegrationTest(MockMvc mockMvc, JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE seats, reservations, shows CASCADE");
    }

    @Test
    void createShow_asAdmin_returns201WithAllSeatsAvailable() throws Exception {
        mockMvc.perform(createShowRequest(ADMIN, THREE_SEATS))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/shows/")))
                .andExpect(jsonPath("$.name").value("friday-night"))
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.confirmed").value(0))
                .andExpect(jsonPath("$.seats.length()").value(3))
                .andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
                .andExpect(jsonPath("$.seats[0].status").value("available"));

        assertEquals(1, count("SELECT count(*) FROM shows WHERE price_paise = 25000"));
        assertEquals(3, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE' "
                + "AND reservation_id IS NULL AND user_id IS NULL"));
    }

    @Test
    void createShow_asUser_returns403AndPersistsNothing() throws Exception {
        mockMvc.perform(createShowRequest(USER, THREE_SEATS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));

        assertEquals(0, count("SELECT count(*) FROM shows"));
    }

    @Test
    void createShow_withoutToken_returns401AndPersistsNothing() throws Exception {
        mockMvc.perform(createShowRequest(null, THREE_SEATS))
                .andExpect(status().isUnauthorized());

        assertEquals(0, count("SELECT count(*) FROM shows"));
    }

    @Test
    void createShow_duplicateSeatLabels_returns400AndPersistsNothing() throws Exception {
        mockMvc.perform(createShowRequest(ADMIN, """
                        {"name": "friday-night", "seats": ["A1", "A2", "A1"], "price_paise": 25000}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_SEATS"));

        assertEquals(0, count("SELECT count(*) FROM shows"));
        assertEquals(0, count("SELECT count(*) FROM seats"));
    }

    @Test
    void createShow_invalidFields_returns400ListingEachField() throws Exception {
        mockMvc.perform(createShowRequest(ADMIN, """
                        {"name": "", "seats": ["A1"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'price_paise')]").exists());

        assertEquals(0, count("SELECT count(*) FROM shows"));
    }

    @Test
    void createShow_malformedJson_returns400() throws Exception {
        mockMvc.perform(createShowRequest(ADMIN, "{\"name\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShow_largeShow_persistsEverySeat() throws Exception {
        String labels = IntStream.rangeClosed(1, 5_000)
                .mapToObj(i -> "\"S" + i + "\"")
                .collect(Collectors.joining(","));

        statistics.clear();
        mockMvc.perform(createShowRequest(ADMIN,
                        "{\"name\": \"big\", \"seats\": [" + labels + "], \"price_paise\": 100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total_seats").value(5_000))
                .andExpect(jsonPath("$.available").value(5_000));

        assertEquals(5_000, count("SELECT count(*) FROM seats"));
        // A merge-based save would prepare one SELECT per seat; batched persist prepares a handful of INSERTs.
        assertTrue(statistics.getPrepareStatementCount() < 100,
                "prepared statements: " + statistics.getPrepareStatementCount());
    }

    @Test
    void getShow_afterCreate_returnsSameSeatsSortedByLabel() throws Exception {
        String showId = createShow(ADMIN, """
                {"name": "friday-night", "seats": ["B1", "A1", "A2"], "price_paise": 25000}""");

        mockMvc.perform(authorized(get("/shows/{id}", showId), USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(showId))
                .andExpect(jsonPath("$.seats[0].seat_label").value("A1"))
                .andExpect(jsonPath("$.seats[1].seat_label").value("A2"))
                .andExpect(jsonPath("$.seats[2].seat_label").value("B1"));
    }

    @Test
    void getShow_withHeldAndConfirmedSeatsInDatabase_returnsCountsThatAddUp() throws Exception {
        String showId = createShow(ADMIN, """
                {"name": "friday-night", "seats": ["A1", "A2", "A3", "A4"], "price_paise": 25000}""");
        UUID reservationId = UUID.randomUUID();
        jdbc.update("INSERT INTO reservations (id, show_id, user_id, idempotency_key, seat_labels, total_paise, "
                        + "status, expires_at) VALUES (?, ?, 'alice', 'k1', '{A1,A2,A3}'::text[], 75000, 'HELD', "
                        + "now() + interval '5 minutes')",
                reservationId, UUID.fromString(showId));
        jdbc.update("UPDATE seats SET status = 'HELD', reservation_id = ?, user_id = 'alice' "
                        + "WHERE show_id = ? AND seat_label IN ('A1', 'A2')",
                reservationId, UUID.fromString(showId));
        jdbc.update("UPDATE seats SET status = 'CONFIRMED', reservation_id = ?, user_id = 'alice' "
                        + "WHERE show_id = ? AND seat_label = 'A3'",
                reservationId, UUID.fromString(showId));

        mockMvc.perform(authorized(get("/shows/{id}", showId), ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_seats").value(4))
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.held").value(2))
                .andExpect(jsonPath("$.confirmed").value(1))
                .andExpect(jsonPath("$.seats[?(@.seat_label == 'A1')].status").value("held"))
                .andExpect(jsonPath("$.seats[?(@.seat_label == 'A3')].status").value("confirmed"))
                .andExpect(jsonPath("$.seats[?(@.seat_label == 'A4')].status").value("available"));
    }

    @Test
    void getShow_onlyReturnsSeatsOfRequestedShow() throws Exception {
        String first = createShow(ADMIN, THREE_SEATS);
        createShow(ADMIN, """
                {"name": "saturday", "seats": ["A1", "A2"], "price_paise": 100}""");

        mockMvc.perform(authorized(get("/shows/{id}", first), USER))
                .andExpect(jsonPath("$.total_seats").value(3));
    }

    @Test
    void getShow_unknownId_returns404() throws Exception {
        mockMvc.perform(authorized(get("/shows/{id}", UUID.randomUUID()), USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SHOW_NOT_FOUND"));
    }

    @Test
    void getShow_invalidUuid_returns400() throws Exception {
        mockMvc.perform(authorized(get("/shows/{id}", "not-a-uuid"), USER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getShow_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/shows/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder createShowRequest(String authorization, String body) {
        return authorized(post("/shows"), authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request,
            String authorization) {
        return authorization == null ? request : request.header(HttpHeaders.AUTHORIZATION, authorization);
    }

    private String createShow(String authorization, String body) throws Exception {
        String response = mockMvc.perform(createShowRequest(authorization, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}

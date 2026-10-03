package com.seatreservation.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The sweeper interval is pushed out so the background job cannot race tests that drive expiry by hand.
@SpringBootTest(properties = "reservation.sweep-interval=PT1H")
@AutoConfigureMockMvc
@Testcontainers
class ReservationControllerIntegrationTest {

    private static final String ADMIN = "Bearer admin:ops-1";
    private static final String ALICE = "Bearer user:alice";
    private static final String BOB = "Bearer user:bob";
    private static final int PRICE_PAISE = 25_000;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final SeatLocker seatLocker;
    private final ExpirySweeper expirySweeper;
    private final ReservationService reservationService;
    private final TransactionTemplate transaction;

    @Autowired
    ReservationControllerIntegrationTest(MockMvc mockMvc, JdbcTemplate jdbc, SeatLocker seatLocker,
            ExpirySweeper expirySweeper, ReservationService reservationService,
            PlatformTransactionManager transactionManager) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.seatLocker = seatLocker;
        this.expirySweeper = expirySweeper;
        this.reservationService = reservationService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE seats, reservations, shows CASCADE");
    }

    @Test
    void reserve_availableSeats_returns201AndHoldsSeats() throws Exception {
        String showId = createShow(10);
        Timestamp seatUpdatedBefore = seatUpdatedAt(showId, "A1");
        Timestamp requestStart = Timestamp.from(Instant.now());

        mockMvc.perform(reserveRequest(ALICE, showId, body("k1", "A2", "A1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.show_id").value(showId))
                .andExpect(jsonPath("$.seats[0]").value("A1"))
                .andExpect(jsonPath("$.seats[1]").value("A2"))
                .andExpect(jsonPath("$.total_paise").value(2 * PRICE_PAISE))
                .andExpect(jsonPath("$.status").value("held"))
                .andExpect(jsonPath("$.expires_at").isNotEmpty());

        assertEquals(1, count("SELECT count(*) FROM reservations WHERE user_id = 'alice' AND status = 'HELD' "
                + "AND total_paise = 50000 AND expires_at - created_at = interval '5 minutes'"));
        assertEquals(2, count("SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id "
                + "WHERE s.status = 'HELD' AND s.user_id = 'alice' AND s.seat_label IN ('A1', 'A2')"));
        assertTrue(seatUpdatedAt(showId, "A1").after(seatUpdatedBefore), "seat updated_at must move forward");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE updated_at >= ?", Integer.class, requestStart));
        assertConsistent(showId);
    }

    @Test
    void lockSeats_labelsRequestedAgainstStorageOrder_returnsSeatsInLabelOrder() throws Exception {
        // Seats are inserted B1 before A1, so an unordered scan of this tiny table returns B1 first.
        UUID showId = UUID.fromString(createShowWithLabels("B1", "A1", "C1"));

        List<String> locked = transaction.execute(status ->
                seatLocker.lockSeats(showId, List.of("C1", "B1", "A1")).stream()
                        .map(seat -> seat.getSeatLabel())
                        .toList());

        assertEquals(List.of("A1", "B1", "C1"), locked);
    }

    @Test
    void reserve_sameKeySameSeatsInAnyOrder_returnsOriginalAndBooksOnce() throws Exception {
        String showId = createShow(10);
        Reply first = reserve(ALICE, showId, body("k1", "A1", "A2"));

        Reply replay = reserve(ALICE, showId, body("k1", "A2", "A1"));

        assertEquals(201, first.status());
        assertEquals(201, replay.status());
        assertEquals(first.id(), replay.id());
        assertEquals(1, count("SELECT count(*) FROM reservations"));
        assertConsistent(showId);
    }

    @Test
    void reserve_sameKeyDifferentSeats_returns409AndBooksNothingNew() throws Exception {
        String showId = createShow(10);
        reserve(ALICE, showId, body("k1", "A1"));

        mockMvc.perform(reserveRequest(ALICE, showId, body("k1", "A2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(1, count("SELECT count(*) FROM reservations"));
        assertEquals(1, count("SELECT count(*) FROM seats WHERE status = 'HELD'"));
        assertConsistent(showId);
    }

    @Test
    void reserve_sameKeyFromAnotherUser_isASeparateReservation() throws Exception {
        String showId = createShow(10);

        assertEquals(201, reserve(ALICE, showId, body("shared-key", "A1")).status());
        assertEquals(201, reserve(BOB, showId, body("shared-key", "A2")).status());

        assertEquals(2, count("SELECT count(*) FROM reservations"));
    }

    @Test
    void reserve_oneSeatTaken_returns409AndWritesNothing() throws Exception {
        String showId = createShow(10);
        reserve(BOB, showId, body("bob-1", "A1"));
        Timestamp a2UpdatedBefore = seatUpdatedAt(showId, "A2");

        mockMvc.perform(reserveRequest(ALICE, showId, body("k1", "A1", "A2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("A1")));

        assertEquals(1, count("SELECT count(*) FROM reservations"));
        assertEquals(1, count("SELECT count(*) FROM seats WHERE status = 'HELD'"));
        assertEquals(a2UpdatedBefore, seatUpdatedAt(showId, "A2"));
        assertConsistent(showId);
    }

    @Test
    void reserve_retryOfDeclinedRequestWithSameKey_triesAgain() throws Exception {
        String showId = createShow(10);
        reserve(BOB, showId, body("bob-1", "A1"));
        assertEquals(409, reserve(ALICE, showId, body("k1", "A1")).status());
        jdbc.update("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL, user_id = NULL "
                + "WHERE show_id = ? AND seat_label = 'A1'", UUID.fromString(showId));

        assertEquals(201, reserve(ALICE, showId, body("k1", "A1")).status());
    }

    @Test
    void reserve_unknownSeat_returns400AndWritesNothing() throws Exception {
        String showId = createShow(10);

        mockMvc.perform(reserveRequest(ALICE, showId, body("k1", "A1", "Z99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_SEATS"));

        assertEquals(0, count("SELECT count(*) FROM reservations"));
        assertEquals(10, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE'"));
    }

    @Test
    void reserve_pushingUserOverFourSeats_returns409AndWritesNothing() throws Exception {
        String showId = createShow(10);
        assertEquals(201, reserve(ALICE, showId, body("k1", "A1", "A2", "A3")).status());

        mockMvc.perform(reserveRequest(ALICE, showId, body("k2", "A4", "A5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SEAT_LIMIT_EXCEEDED"));
        assertEquals(201, reserve(ALICE, showId, body("k3", "A4")).status());

        assertEquals(4, count("SELECT count(*) FROM seats WHERE user_id = 'alice'"));
        assertEquals(2, count("SELECT count(*) FROM reservations"));
        assertConsistent(showId);
    }

    @Test
    void reserve_fiveSeatsInOneRequest_returns409() throws Exception {
        String showId = createShow(10);

        mockMvc.perform(reserveRequest(ALICE, showId, body("k1", "A1", "A2", "A3", "A4", "A5")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SEAT_LIMIT_EXCEEDED"));

        assertEquals(0, count("SELECT count(*) FROM reservations"));
    }

    @Test
    void reserve_fourSeatsInAnotherShow_isNotCountedAgainstTheLimit() throws Exception {
        String first = createShow(10);
        String second = createShow(10);
        assertEquals(201, reserve(ALICE, first, body("k1", "A1", "A2", "A3", "A4")).status());

        assertEquals(201, reserve(ALICE, second, body("k2", "A1", "A2", "A3", "A4")).status());
    }

    @Test
    void reserve_asAdmin_returns403AndWritesNothing() throws Exception {
        String showId = createShow(10);

        mockMvc.perform(reserveRequest(ADMIN, showId, body("k1", "A1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));

        assertEquals(0, count("SELECT count(*) FROM reservations"));
    }

    @Test
    void reserve_withoutToken_returns401AndWritesNothing() throws Exception {
        String showId = createShow(10);

        mockMvc.perform(reserveRequest(null, showId, body("k1", "A1")))
                .andExpect(status().isUnauthorized());

        assertEquals(0, count("SELECT count(*) FROM reservations"));
    }

    @Test
    void reserve_unknownShow_returns404() throws Exception {
        mockMvc.perform(reserveRequest(ALICE, UUID.randomUUID().toString(), body("k1", "A1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SHOW_NOT_FOUND"));
    }

    @Test
    void reserve_duplicateLabelsInRequest_returns400() throws Exception {
        String showId = createShow(10);

        mockMvc.perform(reserveRequest(ALICE, showId, body("k1", "A1", "A1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_SEATS"));

        assertEquals(0, count("SELECT count(*) FROM reservations"));
    }

    @Test
    void reserve_invalidFields_returns400ListingEachField() throws Exception {
        String showId = createShow(10);

        mockMvc.perform(reserveRequest(ALICE, showId, """
                        {"seats": [], "idempotency_key": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'seats')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'idempotency_key')]").exists());
    }

    @Test
    void reserve_500UsersRaceForOneSeat_exactlyOneWins() throws Exception {
        String showId = createShow(1);

        List<Reply> replies = fireConcurrently(IntStream.range(0, 500)
                .<Callable<Reply>>mapToObj(i -> () -> reserve("Bearer user:u" + i, showId, body("k" + i, "A1")))
                .toList());

        assertEquals(1, countStatus(replies, 201));
        assertEquals(499, countStatus(replies, 409));
        assertTrue(replies.stream().filter(reply -> reply.status() == 409)
                .allMatch(reply -> reply.reason().equals("SEATS_UNAVAILABLE")));
        assertEquals(1, count("SELECT count(*) FROM reservations"));
        assertConsistent(showId);
    }

    @Test
    void reserve_thousandUsersForHundredSeats_sellsEachSeatOnce() throws Exception {
        String showId = createShow(100);

        List<Reply> replies = fireConcurrently(IntStream.range(0, 1_000)
                .<Callable<Reply>>mapToObj(i -> () -> reserve("Bearer user:u" + i, showId,
                        body("k" + i, "A" + (i % 100 + 1))))
                .toList());

        assertEquals(100, countStatus(replies, 201));
        assertEquals(900, countStatus(replies, 409));
        assertEquals(100, count("SELECT count(*) FROM seats WHERE status = 'HELD'"));
        assertConsistent(showId);
    }

    @Test
    void reserve_oppositeLabelOrderFromManyUsers_doesNotDeadlock() throws Exception {
        String showId = createShow(2);

        List<Reply> replies = fireConcurrently(IntStream.range(0, 200)
                .<Callable<Reply>>mapToObj(i -> () -> reserve("Bearer user:u" + i, showId,
                        i % 2 == 0 ? body("k" + i, "A1", "A2") : body("k" + i, "A2", "A1")))
                .toList());

        assertEquals(1, countStatus(replies, 201));
        assertEquals(199, countStatus(replies, 409));
        assertEquals(2, count("SELECT count(*) FROM seats WHERE status = 'HELD'"));
        assertConsistent(showId);
    }

    @Test
    void reserve_oneUserTenConcurrentRequests_holdsExactlyFourSeats() throws Exception {
        String showId = createShow(10);

        List<Reply> replies = fireConcurrently(IntStream.rangeClosed(1, 10)
                .<Callable<Reply>>mapToObj(i -> () -> reserve(ALICE, showId, body("k" + i, "A" + i)))
                .toList());

        assertEquals(4, countStatus(replies, 201));
        assertEquals(6, countStatus(replies, 409));
        assertTrue(replies.stream().filter(reply -> reply.status() == 409)
                .allMatch(reply -> reply.reason().equals("SEAT_LIMIT_EXCEEDED")));
        assertEquals(4, count("SELECT count(*) FROM seats WHERE user_id = 'alice'"));
        assertConsistent(showId);
    }

    @Test
    void reserve_sameKeyFiftyTimesAtOnce_createsOneReservationWithOneAnswer() throws Exception {
        String showId = createShow(10);

        List<Reply> replies = fireConcurrently(IntStream.range(0, 50)
                .<Callable<Reply>>mapToObj(i -> () -> reserve(ALICE, showId, body("same-key", "A1", "A2")))
                .toList());

        assertEquals(50, countStatus(replies, 201));
        assertEquals(1, replies.stream().map(Reply::id).distinct().count());
        assertEquals(1, count("SELECT count(*) FROM reservations"));
        assertEquals(2, count("SELECT count(*) FROM seats WHERE status = 'HELD'"));
        assertConsistent(showId);
    }

    @Test
    void cancel_asOwner_returns200AndFreesSeats() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1", "A2");
        Timestamp seatUpdatedBefore = seatUpdatedAt(showId, "A1");
        Timestamp cancelStart = Timestamp.from(Instant.now());

        Reply reply = cancel(ALICE, id);

        assertEquals(200, reply.status());
        assertEquals("cancelled", JsonPath.read(reply.body(), "$.status"));
        assertEquals("CANCELLED", reservationStatus(id));
        assertEquals(2, count("SELECT count(*) FROM seats WHERE seat_label IN ('A1', 'A2') "
                + "AND status = 'AVAILABLE' AND reservation_id IS NULL AND user_id IS NULL"));
        assertTrue(seatUpdatedAt(showId, "A1").after(seatUpdatedBefore), "seat updated_at must move forward");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE status = 'CANCELLED' AND updated_at >= ?",
                Integer.class, cancelStart));
        assertConsistent(showId);
    }

    @Test
    void cancel_byAnotherUser_returns403AndChangesNothing() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");

        Reply reply = cancel(BOB, id);

        assertEquals(403, reply.status());
        assertEquals("NOT_RESERVATION_OWNER", reply.reason());
        assertEquals("HELD", reservationStatus(id));
        assertEquals(1, count("SELECT count(*) FROM seats WHERE status = 'HELD' AND user_id = 'alice'"));
    }

    @Test
    void cancel_unknownReservation_returns404() throws Exception {
        Reply reply = cancel(ALICE, UUID.randomUUID().toString());

        assertEquals(404, reply.status());
        assertEquals("RESERVATION_NOT_FOUND", reply.reason());
    }

    @Test
    void cancel_asAdmin_returns403AndChangesNothing() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");

        assertEquals(403, cancel(ADMIN, id).status());

        assertEquals("HELD", reservationStatus(id));
    }

    @Test
    void cancel_withoutToken_returns401AndChangesNothing() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");

        assertEquals(401, cancel(null, id).status());

        assertEquals("HELD", reservationStatus(id));
    }

    @Test
    void cancel_thenAnotherUserReserves_secondUserGets201() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");
        assertEquals(409, reserve(BOB, showId, body("k2", "A1")).status());

        assertEquals(200, cancel(ALICE, id).status());

        assertEquals(201, reserve(BOB, showId, body("k2", "A1")).status());
        assertEquals(1, count("SELECT count(*) FROM seats WHERE status = 'HELD' AND user_id = 'bob'"));
        assertConsistent(showId);
    }

    @Test
    void cancel_calledTwice_returns200BothTimes() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");

        assertEquals(200, cancel(ALICE, id).status());
        assertEquals(200, cancel(ALICE, id).status());

        assertEquals("CANCELLED", reservationStatus(id));
        assertEquals(10, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE'"));
        assertConsistent(showId);
    }

    @Test
    void cancel_afterHoldExpired_returns409() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");
        expireNow(id);
        assertEquals(1, expirySweeper.sweepOnce());

        Reply reply = cancel(ALICE, id);

        assertEquals(409, reply.status());
        assertEquals("RESERVATION_EXPIRED", reply.reason());
        assertEquals("EXPIRED", reservationStatus(id));
    }

    @Test
    void cancel_freesTheUsersSeatLimit() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1", "A2", "A3", "A4");
        assertEquals(409, reserve(ALICE, showId, body("k2", "A5")).status());

        assertEquals(200, cancel(ALICE, id).status());

        assertEquals(201, reserve(ALICE, showId, body("k3", "A1", "A2", "A3", "A4")).status());
        assertConsistent(showId);
    }

    @Test
    void sweepOnce_expiredHold_releasesSeatsAndLeavesLiveHoldsAlone() throws Exception {
        String showId = createShow(10);
        String expired = reserved(ALICE, showId, "k1", "A1", "A2");
        String live = reserved(BOB, showId, "k2", "A3");
        expireNow(expired);
        Timestamp seatUpdatedBefore = seatUpdatedAt(showId, "A1");

        assertEquals(1, expirySweeper.sweepOnce());

        assertEquals("EXPIRED", reservationStatus(expired));
        assertEquals("HELD", reservationStatus(live));
        assertEquals(2, count("SELECT count(*) FROM seats WHERE seat_label IN ('A1', 'A2') "
                + "AND status = 'AVAILABLE' AND reservation_id IS NULL AND user_id IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM seats WHERE seat_label = 'A3' AND status = 'HELD'"));
        assertTrue(seatUpdatedAt(showId, "A1").after(seatUpdatedBefore), "seat updated_at must move forward");
        assertConsistent(showId);
    }

    @Test
    void sweepOnce_calledTwice_secondCallDoesNothing() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");
        expireNow(id);

        assertEquals(1, expirySweeper.sweepOnce());
        assertEquals(0, expirySweeper.sweepOnce());

        assertEquals("EXPIRED", reservationStatus(id));
        assertConsistent(showId);
    }

    @Test
    void expire_holdCancelledAfterItWasFoundExpired_isANoOp() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1", "A2");
        expireNow(id);
        List<UUID> staleIds = reservationService.findExpiredReservationIds();
        assertTrue(staleIds.contains(UUID.fromString(id)), "the hold should have been found as expired");
        assertEquals(200, cancel(ALICE, id).status());

        assertFalse(reservationService.expire(UUID.fromString(id)));

        assertEquals("CANCELLED", reservationStatus(id));
        assertEquals(10, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE'"));
        assertFalse(reservationService.expire(UUID.randomUUID()));
        assertConsistent(showId);
    }

    @Test
    void reserve_replayOfCancelledReservation_returns409NotActive() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1");
        assertEquals(200, cancel(ALICE, id).status());

        Reply sameSeats = reserve(ALICE, showId, body("k1", "A1"));
        Reply otherSeats = reserve(ALICE, showId, body("k1", "A2"));

        assertEquals(409, sameSeats.status());
        assertEquals("RESERVATION_NOT_ACTIVE", sameSeats.reason());
        assertEquals(409, otherSeats.status());
        assertEquals("IDEMPOTENCY_KEY_REUSED", otherSeats.reason());
        assertEquals(1, count("SELECT count(*) FROM reservations"));
        assertEquals(10, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE'"));
    }

    @Test
    void reserve_replayOfExpiredReservation_returns409NotActive() throws Exception {
        String showId = createShow(10);
        expireNow(reserved(ALICE, showId, "k1", "A1"));
        assertEquals(1, expirySweeper.sweepOnce());

        Reply replay = reserve(ALICE, showId, body("k1", "A1"));

        assertEquals(409, replay.status());
        assertEquals("RESERVATION_NOT_ACTIVE", replay.reason());
        assertEquals(1, count("SELECT count(*) FROM reservations"));
    }

    @Test
    void cancel_manyConcurrentCancelsOfOneReservation_allReturn200() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1", "A2");

        List<Reply> replies = fireConcurrently(IntStream.range(0, 30)
                .<Callable<Reply>>mapToObj(i -> () -> cancel(ALICE, id))
                .toList());

        assertEquals(30, countStatus(replies, 200));
        assertEquals("CANCELLED", reservationStatus(id));
        assertEquals(10, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE'"));
        assertConsistent(showId);
    }

    @Test
    void cancel_racingExpirySweep_endsInOneTerminalStateAndEveryCancelAgrees() throws Exception {
        String showId = createShow(10);
        String id = reserved(ALICE, showId, "k1", "A1", "A2");
        expireNow(id);
        List<Callable<Object>> work = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> work.add(() -> cancel(ALICE, id)));
        IntStream.range(0, 5).forEach(i -> work.add(() -> expirySweeper.sweepOnce()));

        List<Reply> cancels = fireConcurrently(work).stream()
                .filter(Reply.class::isInstance).map(Reply.class::cast).toList();

        String finalStatus = reservationStatus(id);
        assertTrue(finalStatus.equals("CANCELLED") || finalStatus.equals("EXPIRED"), finalStatus);
        // Whichever side won, every cancel must have been told the same story.
        long expectedStatus = finalStatus.equals("CANCELLED") ? 200 : 409;
        assertEquals(20, countStatus(cancels, (int) expectedStatus));
        assertEquals(10, count("SELECT count(*) FROM seats WHERE status = 'AVAILABLE'"));
        assertConsistent(showId);
    }

    @Test
    void cancel_racingReserveOfTheSameSeat_neitherDeadlocksNorDoubleBooks() throws Exception {
        String showId = createShow(1);
        String id = reserved(ALICE, showId, "k0", "A1");
        List<Callable<Reply>> work = new ArrayList<>();
        work.add(() -> cancel(ALICE, id));
        IntStream.range(1, 21).forEach(i -> work.add(
                () -> reserve("Bearer user:u" + i, showId, body("k" + i, "A1"))));

        List<Reply> replies = fireConcurrently(work);

        assertEquals(200, replies.get(0).status());
        List<Reply> reserves = replies.subList(1, replies.size());
        assertTrue(countStatus(reserves, 201) <= 1, "at most one buyer can win the freed seat");
        assertEquals(20, countStatus(reserves, 201) + countStatus(reserves, 409));
        assertConsistent(showId);
    }

    private String reserved(String authorization, String showId, String key, String... labels) throws Exception {
        Reply reply = reserve(authorization, showId, body(key, labels));
        assertEquals(201, reply.status());
        return reply.id();
    }

    private String reservationStatus(String reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class,
                UUID.fromString(reservationId));
    }

    /** available + held + confirmed must equal total, and every owned seat must belong to a matching reservation. */
    private void assertConsistent(String showId) throws Exception {
        String response = mockMvc.perform(authorized(get("/shows/{id}", showId), ADMIN))
                .andReturn().getResponse().getContentAsString();
        int total = JsonPath.read(response, "$.total_seats");
        int available = JsonPath.read(response, "$.available");
        int held = JsonPath.read(response, "$.held");
        int confirmed = JsonPath.read(response, "$.confirmed");
        assertEquals(total, available + held + confirmed);

        assertEquals(0, count("SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id "
                + "WHERE s.user_id <> r.user_id OR NOT (s.seat_label = ANY (r.seat_labels))"));
        assertEquals(count("SELECT count(*) FROM seats WHERE status = 'HELD'"),
                count("SELECT coalesce(sum(cardinality(seat_labels)), 0) FROM reservations WHERE status = 'HELD'"));
    }

    private <T> List<T> fireConcurrently(List<Callable<T>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<T> replies = new ArrayList<>();
            for (Future<T> future : futures) {
                replies.add(future.get(60, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private Reply cancel(String authorization, String reservationId) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        authorized(post("/reservations/{id}/cancel", reservationId), authorization))
                .andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsString());
    }

    private void expireNow(String reservationId) {
        jdbc.update("UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                UUID.fromString(reservationId));
    }

    private Reply reserve(String authorization, String showId, String body) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(reserveRequest(authorization, showId, body))
                .andReturn().getResponse();
        return new Reply(response.getStatus(), response.getContentAsString());
    }

    private MockHttpServletRequestBuilder reserveRequest(String authorization, String showId, String body) {
        return authorized(post("/shows/{id}/reserve", showId), authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request,
            String authorization) {
        return authorization == null ? request : request.header(HttpHeaders.AUTHORIZATION, authorization);
    }

    private static String body(String idempotencyKey, String... labels) {
        String quoted = java.util.Arrays.stream(labels).map(label -> "\"" + label + "\"")
                .collect(Collectors.joining(","));
        return "{\"seats\": [" + quoted + "], \"idempotency_key\": \"" + idempotencyKey + "\"}";
    }

    private String createShow(int seatCount) throws Exception {
        return createShowWithLabels(IntStream.rangeClosed(1, seatCount).mapToObj(i -> "A" + i).toArray(String[]::new));
    }

    private String createShowWithLabels(String... labels) throws Exception {
        String seats = Arrays.stream(labels).map(label -> "\"" + label + "\"").collect(Collectors.joining(","));
        String response = mockMvc.perform(authorized(post("/shows"), ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"friday-night\", \"seats\": [" + seats + "], \"price_paise\": "
                                + PRICE_PAISE + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private Timestamp seatUpdatedAt(String showId, String seatLabel) {
        return jdbc.queryForObject("SELECT updated_at FROM seats WHERE show_id = ? AND seat_label = ?",
                Timestamp.class, UUID.fromString(showId), seatLabel);
    }

    private static long countStatus(List<Reply> replies, int status) {
        return replies.stream().filter(reply -> reply.status() == status).count();
    }

    private int count(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private record Reply(int status, String body) {

        String id() {
            return JsonPath.read(body, "$.id");
        }

        String reason() {
            return JsonPath.read(body, "$.reason");
        }
    }
}

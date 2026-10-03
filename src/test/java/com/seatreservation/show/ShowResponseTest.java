package com.seatreservation.show;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ShowResponseTest {

    private static final UUID SHOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void of_mixedStatuses_countsPerStatusAndAddsUpToTotal() {
        List<Seat> seats = List.of(
                seat("A1", SeatStatus.AVAILABLE),
                seat("A2", SeatStatus.HELD),
                seat("A3", SeatStatus.HELD),
                seat("A4", SeatStatus.CONFIRMED),
                seat("A5", SeatStatus.AVAILABLE),
                seat("A6", SeatStatus.AVAILABLE));

        ShowResponse response = ShowResponse.of(show(), seats);

        assertEquals(6, response.totalSeats());
        assertEquals(3, response.available());
        assertEquals(2, response.held());
        assertEquals(1, response.confirmed());
        assertEquals(response.totalSeats(), response.available() + response.held() + response.confirmed());
    }

    @Test
    void of_allSeatsAvailable_countsOnlyAvailable() {
        ShowResponse response = ShowResponse.of(show(), List.of(
                seat("A1", SeatStatus.AVAILABLE), seat("A2", SeatStatus.AVAILABLE)));

        assertEquals(2, response.available());
        assertEquals(0, response.held());
        assertEquals(0, response.confirmed());
    }

    @Test
    void of_unsortedSeats_returnsSeatsSortedByLabel() {
        ShowResponse response = ShowResponse.of(show(), List.of(
                seat("B1", SeatStatus.AVAILABLE),
                seat("A2", SeatStatus.HELD),
                seat("A1", SeatStatus.AVAILABLE)));

        assertEquals(
                List.of(
                        new SeatResponse("A1", SeatStatus.AVAILABLE),
                        new SeatResponse("A2", SeatStatus.HELD),
                        new SeatResponse("B1", SeatStatus.AVAILABLE)),
                response.seats());
    }

    @Test
    void of_anyShow_copiesShowFields() {
        ShowResponse response = ShowResponse.of(show(), List.of(seat("A1", SeatStatus.AVAILABLE)));

        assertEquals(SHOW_ID, response.id());
        assertEquals("friday-night", response.name());
        assertEquals(25000L, response.pricePaise());
    }

    private static Show show() {
        Show show = new Show("friday-night", 25000, Instant.EPOCH);
        ReflectionTestUtils.setField(show, "id", SHOW_ID);
        return show;
    }

    private static Seat seat(String label, SeatStatus status) {
        Seat seat = Seat.available(SHOW_ID, label, Instant.EPOCH);
        ReflectionTestUtils.setField(seat, "status", status);
        return seat;
    }
}

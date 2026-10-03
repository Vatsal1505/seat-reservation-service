package com.seatreservation.show;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int totalSeats,
        int available,
        int held,
        int confirmed,
        List<SeatResponse> seats) {

    /** Counts come from the same seat list that is returned, so they always add up to the total. */
    static ShowResponse of(Show show, List<Seat> seats) {
        List<SeatResponse> sorted = seats.stream()
                .sorted(Comparator.comparing(Seat::getSeatLabel))
                .map(SeatResponse::of)
                .toList();
        return new ShowResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                seats.size(),
                count(seats, SeatStatus.AVAILABLE),
                count(seats, SeatStatus.HELD),
                count(seats, SeatStatus.CONFIRMED),
                sorted);
    }

    private static int count(List<Seat> seats, SeatStatus status) {
        return (int) seats.stream().filter(seat -> seat.getStatus() == status).count();
    }
}

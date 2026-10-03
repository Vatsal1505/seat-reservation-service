package com.seatreservation.show;

import com.seatreservation.error.InvalidRequestException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final Clock clock;

    public ShowService(ShowRepository shows, SeatRepository seats, Clock clock) {
        this.shows = shows;
        this.seats = seats;
        this.clock = clock;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        if (new HashSet<>(request.seats()).size() != request.seats().size()) {
            throw new InvalidRequestException("DUPLICATE_SEATS", "Seat labels must be unique within a show.");
        }
        Instant now = Instant.now(clock);
        Show show = shows.save(new Show(request.name(), request.pricePaise(), now));
        List<Seat> created = seats.saveAll(request.seats().stream()
                .map(label -> Seat.available(show.getId(), label, now))
                .toList());
        log.info("show created. showId={}, seatCount={}", show.getId(), created.size());
        return ShowResponse.of(show, created);
    }

    @Transactional(readOnly = true)
    public ShowResponse get(UUID showId) {
        Show show = shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        return ShowResponse.of(show, seats.findByIdShowId(showId));
    }
}

package com.seatreservation.reservation;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final ReservationService reservationService;

    public ExpirySweeper(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    // Initial delay equals the interval so a freshly started instance does not sweep before it is serving.
    @Scheduled(fixedDelayString = "${reservation.sweep-interval}",
            initialDelayString = "${reservation.sweep-interval}")
    void sweep() {
        // A scheduled method that throws is not run again, so nothing may escape this try.
        try {
            sweepOnce();
        } catch (RuntimeException ex) {
            log.error("expiry sweep failed", ex);
        }
    }

    /** One transaction per hold, so a single bad row cannot block the others. Returns how many were expired. */
    int sweepOnce() {
        int expired = 0;
        for (UUID reservationId : reservationService.findExpiredReservationIds()) {
            try {
                if (reservationService.expire(reservationId)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.error("could not expire hold. reservationId={}", reservationId, ex);
            }
        }
        return expired;
    }
}

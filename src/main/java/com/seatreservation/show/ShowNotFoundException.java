package com.seatreservation.show;

import com.seatreservation.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class ShowNotFoundException extends ApiException {

    public ShowNotFoundException(UUID showId) {
        super(HttpStatus.NOT_FOUND, "SHOW_NOT_FOUND", "Show not found: " + showId);
    }
}

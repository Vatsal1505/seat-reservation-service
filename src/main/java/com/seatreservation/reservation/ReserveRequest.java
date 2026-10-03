package com.seatreservation.reservation;

import com.seatreservation.error.InvalidRequestException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The list is capped only to bound the lock query; the per-user seat limit is enforced by the service as a 409.
public record ReserveRequest(
        @NotEmpty @Size(max = 50)
        List<@NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9._-]+",
                message = "must contain only letters, digits, '.', '_' or '-'") String> seats,
        @NotBlank @Size(max = 128) String idempotencyKey) {

    Set<String> distinctSeatLabels() {
        Set<String> distinct = new HashSet<>(seats);
        if (distinct.size() != seats.size()) {
            throw new InvalidRequestException("DUPLICATE_SEATS", "Seat labels must be unique within a request.");
        }
        return distinct;
    }
}

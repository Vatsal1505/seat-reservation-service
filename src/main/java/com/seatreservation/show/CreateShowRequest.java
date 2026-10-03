package com.seatreservation.show;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateShowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotEmpty @Size(max = 50_000)
        List<@NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9._-]+",
                message = "must contain only letters, digits, '.', '_' or '-'") String> seats,
        @NotNull @Positive Long pricePaise) {
}

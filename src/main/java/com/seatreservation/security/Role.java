package com.seatreservation.security;

import java.util.Arrays;
import java.util.Optional;

public enum Role {
    ADMIN,
    USER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromToken(String value) {
        return Arrays.stream(values())
                .filter(role -> role.name().equalsIgnoreCase(value))
                .findFirst();
    }
}

package com.seatreservation.security;

public record AuthenticatedUser(String userId, Role role) {
}

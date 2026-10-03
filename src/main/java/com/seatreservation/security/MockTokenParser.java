package com.seatreservation.security;

import java.util.Optional;
import java.util.regex.Pattern;

/** Parses the mock {@code Authorization: Bearer <role>:<userId>} header. The token is unsigned by design. */
public final class MockTokenParser {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private MockTokenParser() {
    }

    public static Optional<AuthenticatedUser> parse(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        int separator = token.indexOf(':');
        if (separator <= 0) {
            return Optional.empty();
        }
        String userId = token.substring(separator + 1);
        if (!USER_ID.matcher(userId).matches()) {
            return Optional.empty();
        }
        return Role.fromToken(token.substring(0, separator))
                .map(role -> new AuthenticatedUser(userId, role));
    }
}

package com.seatreservation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MockTokenParserTest {

    @Test
    void parse_userToken_returnsUserRoleAndId() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer user:alice");

        assertEquals(new AuthenticatedUser("alice", Role.USER), result.orElseThrow());
    }

    @Test
    void parse_adminToken_returnsAdminRoleAndId() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer admin:ops-1");

        assertEquals(new AuthenticatedUser("ops-1", Role.ADMIN), result.orElseThrow());
    }

    @Test
    void parse_upperCaseRole_isAccepted() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer ADMIN:ops-1");

        assertEquals(Role.ADMIN, result.orElseThrow().role());
    }

    @Test
    void parse_lowerCaseBearerScheme_isAccepted() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("bearer user:alice");

        assertEquals("alice", result.orElseThrow().userId());
    }

    @Test
    void parse_surroundingWhitespace_isTrimmed() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer   user:alice  ");

        assertEquals(new AuthenticatedUser("alice", Role.USER), result.orElseThrow());
    }

    @Test
    void parse_userIdWithAllAllowedCharacters_isAccepted() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer user:Al.ice_01@corp-x");

        assertEquals("Al.ice_01@corp-x", result.orElseThrow().userId());
    }

    @Test
    void parse_userIdOfMaximumLength_isAccepted() {
        String userId = "a".repeat(64);

        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer user:" + userId);

        assertEquals(userId, result.orElseThrow().userId());
    }

    @Test
    void parse_userIdOverMaximumLength_isRejected() {
        Optional<AuthenticatedUser> result = MockTokenParser.parse("Bearer user:" + "a".repeat(65));

        assertTrue(result.isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "Basic user:alice",
        "user:alice",
        "Bearer",
        "Bearer ",
        "Bearer alice",
        "Bearer :alice",
        "Bearer superuser:alice",
        "Bearer user:",
        "Bearer user:al ice",
        "Bearer user:al/ice",
        "Bearer user:al:ice"
    })
    void parse_malformedHeader_returnsEmpty(String header) {
        assertTrue(MockTokenParser.parse(header).isEmpty(), "should reject: " + header);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Admin"})
    void fromToken_anyCaseOfAdmin_returnsAdmin(String value) {
        assertEquals(Optional.of(Role.ADMIN), Role.fromToken(value));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"superuser", "ROLE_ADMIN", " admin"})
    void fromToken_unknownValue_returnsEmpty(String value) {
        assertTrue(Role.fromToken(value).isEmpty());
    }

    @Test
    void authority_eachRole_hasRolePrefix() {
        assertEquals("ROLE_ADMIN", Role.ADMIN.authority());
        assertEquals("ROLE_USER", Role.USER.authority());
    }
}

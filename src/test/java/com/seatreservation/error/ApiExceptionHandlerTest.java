package com.seatreservation.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

class ApiExceptionHandlerTest {

    private static final String INTERNAL_DETAIL = "jdbc:postgresql://db-internal:5432/seats refused connection";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    static Stream<Exception> databaseFailures() {
        return Stream.of(
                new CannotCreateTransactionException(INTERNAL_DETAIL),
                new DataAccessResourceFailureException(INTERNAL_DETAIL),
                new CannotAcquireLockException(INTERNAL_DETAIL));
    }

    @ParameterizedTest
    @MethodSource("databaseFailures")
    void handleDatabaseUnavailable_connectionOrLockFailure_returns503WithRetryAfterAndNoInternalDetail(
            Exception failure) {
        ResponseEntity<ProblemDetail> response = handler.handleDatabaseUnavailable(failure);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        ProblemDetail body = response.getBody();
        assertNotNull(body);
        assertEquals("DATABASE_UNAVAILABLE", body.getProperties().get("reason"));
        assertFalse(body.getDetail().contains("db-internal"), "internal connection details must not reach the client");
    }
}

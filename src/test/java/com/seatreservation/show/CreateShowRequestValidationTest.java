package com.seatreservation.show;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreateShowRequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validate_validRequest_hasNoViolations() {
        assertTrue(violatedPaths(new CreateShowRequest("friday-night", List.of("A1", "A2"), 25000L)).isEmpty());
    }

    @Test
    void validate_seatLabelsWithAllowedPunctuation_hasNoViolations() {
        assertTrue(violatedPaths(new CreateShowRequest("show", List.of("A.1", "B_2", "C-3"), 1L)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void validate_blankName_violatesName(String name) {
        assertViolatesOnly("name", new CreateShowRequest(name, List.of("A1"), 25000L));
    }

    @Test
    void validate_nameOverMaximumLength_violatesName() {
        assertViolatesOnly("name", new CreateShowRequest("n".repeat(201), List.of("A1"), 25000L));
    }

    @Test
    void validate_nullSeats_violatesSeats() {
        assertViolatesOnly("seats", new CreateShowRequest("show", null, 25000L));
    }

    @Test
    void validate_emptySeats_violatesSeats() {
        assertViolatesOnly("seats", new CreateShowRequest("show", List.of(), 25000L));
    }

    @Test
    void validate_moreSeatsThanMaximum_violatesSeats() {
        List<String> labels = IntStream.rangeClosed(1, 50_001).mapToObj(i -> "S" + i).toList();

        assertViolatesOnly("seats", new CreateShowRequest("show", labels, 25000L));
    }

    @Test
    void validate_exactlyMaximumSeats_hasNoViolations() {
        List<String> labels = IntStream.rangeClosed(1, 50_000).mapToObj(i -> "S" + i).toList();

        assertTrue(violatedPaths(new CreateShowRequest("show", labels, 25000L)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "A 1", "A/1", "A1\n", "é1"})
    void validate_invalidSeatLabel_violatesThatSeat(String label) {
        assertViolatesOnly("seats[0]", new CreateShowRequest("show", List.of(label), 25000L));
    }

    @Test
    void validate_seatLabelOverMaximumLength_violatesThatSeat() {
        assertViolatesOnly("seats[1]", new CreateShowRequest("show", List.of("A1", "A".repeat(33)), 25000L));
    }

    @Test
    void validate_nullPrice_violatesPricePaise() {
        assertViolatesOnly("pricePaise", new CreateShowRequest("show", List.of("A1"), null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void validate_nonPositivePrice_violatesPricePaise(long price) {
        assertViolatesOnly("pricePaise", new CreateShowRequest("show", List.of("A1"), price));
    }

    private static void assertViolatesOnly(String pathPrefix, CreateShowRequest request) {
        Set<String> paths = violatedPaths(request);
        assertFalse(paths.isEmpty(), "expected a violation on " + pathPrefix);
        assertTrue(paths.stream().allMatch(path -> path.startsWith(pathPrefix)),
                "expected violations only on " + pathPrefix + " but got " + paths);
    }

    private static Set<String> violatedPaths(CreateShowRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}

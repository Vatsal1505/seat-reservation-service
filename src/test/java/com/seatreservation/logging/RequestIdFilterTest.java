package com.seatreservation.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> seenInChain = new AtomicReference<>();
    private final FilterChain recordingChain = (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));

    @Test
    void doFilter_noIncomingId_generatesUuidForHeaderAndLogContext() throws Exception {
        filter.doFilter(request, response, recordingChain);

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertDoesNotThrow(() -> UUID.fromString(header));
        assertEquals(header, seenInChain.get());
    }

    @Test
    void doFilter_acceptableIncomingId_isEchoedAndUsedInLogContext() throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "client-req_42.A");

        filter.doFilter(request, response, recordingChain);

        assertEquals("client-req_42.A", response.getHeader(RequestIdFilter.HEADER));
        assertEquals("client-req_42.A", seenInChain.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "abc\ndef", "", "x\"}{"})
    void doFilter_unacceptableIncomingId_isReplacedByGeneratedUuid(String incoming) throws Exception {
        assertReplaced(incoming);
    }

    @Test
    void doFilter_incomingIdLongerThan64Characters_isReplacedByGeneratedUuid() throws Exception {
        assertReplaced("a".repeat(65));
    }

    @Test
    void doFilter_afterNormalCompletion_clearsLogContext() throws Exception {
        filter.doFilter(request, response, recordingChain);

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void doFilter_chainThrows_stillClearsLogContext() {
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, failingChain));

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    private void assertReplaced(String incoming) throws Exception {
        request.addHeader(RequestIdFilter.HEADER, incoming);

        filter.doFilter(request, response, recordingChain);

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertNotEquals(incoming, header);
        assertDoesNotThrow(() -> UUID.fromString(header));
        assertEquals(header, seenInChain.get());
    }
}

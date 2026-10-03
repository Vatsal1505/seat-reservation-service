package com.seatreservation.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatreservation.error.ApiExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityWiringTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityProblemHandler.class, ApiExceptionHandler.class,
        SecurityWiringTest.ProbeController.class})
class SecurityWiringTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void request_withoutToken_returns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/probe/any"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.reason").value("UNAUTHENTICATED"));
    }

    @Test
    void request_withMalformedToken_returns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/probe/any").header(HttpHeaders.AUTHORIZATION, "Bearer nobody"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("UNAUTHENTICATED"));
    }

    @Test
    void adminEndpoint_withUserToken_returns403ProblemDetail() throws Exception {
        mockMvc.perform(get("/probe/admin").header(HttpHeaders.AUTHORIZATION, "Bearer user:alice"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("FORBIDDEN"));
    }

    @Test
    void adminEndpoint_withAdminToken_returns200() throws Exception {
        mockMvc.perform(get("/probe/admin").header(HttpHeaders.AUTHORIZATION, "Bearer admin:ops-1"))
                .andExpect(status().isOk());
    }

    @Test
    void userEndpoint_withAdminToken_returns403() throws Exception {
        mockMvc.perform(get("/probe/user").header(HttpHeaders.AUTHORIZATION, "Bearer admin:ops-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userEndpoint_withUserToken_returns200() throws Exception {
        mockMvc.perform(get("/probe/user").header(HttpHeaders.AUTHORIZATION, "Bearer user:alice"))
                .andExpect(status().isOk());
    }

    @Test
    void anyEndpoint_withValidToken_exposesUserIdFromToken() throws Exception {
        mockMvc.perform(get("/probe/any").header(HttpHeaders.AUTHORIZATION, "Bearer user:alice"))
                .andExpect(status().isOk())
                .andExpect(content().string("alice"));
    }

    @Test
    void unknownPath_withValidToken_returns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/probe/missing").header(HttpHeaders.AUTHORIZATION, "Bearer user:alice"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void echo_withBlankField_returns400ListingSnakeCaseFieldNames() throws Exception {
        mockMvc.perform(post("/probe/echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user:alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("display_name"));
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/admin")
        @PreAuthorize("hasRole('ADMIN')")
        String admin() {
            return "ok";
        }

        @GetMapping("/user")
        @PreAuthorize("hasRole('USER')")
        String user() {
            return "ok";
        }

        @GetMapping("/any")
        @PreAuthorize("isAuthenticated()")
        String any(@AuthenticationPrincipal AuthenticatedUser user) {
            return user.userId();
        }

        @PostMapping("/echo")
        @PreAuthorize("isAuthenticated()")
        String echo(@Valid @RequestBody EchoRequest request) {
            return request.displayName();
        }
    }

    record EchoRequest(@NotBlank String displayName) {
    }
}

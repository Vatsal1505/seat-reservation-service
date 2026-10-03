package com.seatreservation.health;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The last test stops the database for good, so this class has its own container and a fixed method order.
@SpringBootTest(properties = {
        "reservation.sweep-interval=PT1H",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000"})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthEndpointsIntegrationTest {

    private static final String LIVENESS = "/actuator/health/liveness";
    private static final String READINESS = "/actuator/health/readiness";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private final MockMvc mockMvc;
    private final DataSource mainPool;

    @Autowired
    HealthEndpointsIntegrationTest(MockMvc mockMvc, DataSource mainPool) {
        this.mockMvc = mockMvc;
        this.mainPool = mainPool;
    }

    @Test
    @Order(1)
    void health_databaseUpAndNoToken_bothProbesReturn200UpWithoutDetails() throws Exception {
        for (String probe : new String[] {LIVENESS, READINESS}) {
            mockMvc.perform(get(probe))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(jsonPath("$.details").doesNotExist());
        }
    }

    @Test
    @Order(2)
    void health_mainPoolExhausted_bothProbesStayUp() throws Exception {
        try (Connection first = mainPool.getConnection(); Connection second = mainPool.getConnection()) {
            assertThrows(SQLTransientConnectionException.class, mainPool::getConnection,
                    "the main pool must really be exhausted for this test to mean anything");

            for (String probe : new String[] {LIVENESS, READINESS}) {
                mockMvc.perform(get(probe))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("UP"));
            }
        }
    }

    @Test
    @Order(3)
    void health_databaseStopped_probesReturn503DownAndReserveReturns503() throws Exception {
        String created = mockMvc.perform(post("/shows")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin:ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"friday-night\", \"seats\": [\"A1\"], \"price_paise\": 25000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String showId = JsonPath.read(created, "$.id");

        POSTGRES.stop();

        for (String probe : new String[] {LIVENESS, READINESS}) {
            mockMvc.perform(get(probe))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(jsonPath("$.details").doesNotExist());
        }
        mockMvc.perform(post("/shows/{id}/reserve", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user:alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\": [\"A1\"], \"idempotency_key\": \"k1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.reason").value("DATABASE_UNAVAILABLE"));
    }
}

package com.demo.backend;

import com.demo.backend.service.AiClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Health and metrics live on the (never published) management port, not on the public API port. */
// Spring Boot disables metrics exporters in tests by default; this test is about the exporter, so turn it on.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.prometheus.metrics.export.enabled=true")
class ManagementEndpointsTest {
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @MockBean AiClientService ai;
    @Autowired RestClient.Builder http;

    private int status(int p, String path) {
        return http.build().get().uri("http://localhost:" + p + path).exchange((req, res) -> res.getStatusCode().value());
    }

    private String body(int p, String path) {
        return http.build().get().uri("http://localhost:" + p + path).retrieve().body(String.class);
    }

    @Test
    void publicPortHasNoActuatorEndpoints() {
        assertNotEquals(port, managementPort);
        assertEquals(404, status(port, "/actuator/health"));
        assertEquals(404, status(port, "/actuator/prometheus"));
    }

    @Test
    void managementPortServesHealthAndMetricsOnly() {
        assertEquals("{\"status\":\"UP\"}", body(managementPort, "/actuator/health"));
        // Not exposed (only health and prometheus are), and not permitted without login either.
        int env = status(managementPort, "/actuator/env");
        assertTrue(env == 401 || env == 404, "env endpoint must not be readable, got " + env);

        // Generate a login so the custom counter exists, then scrape.
        http.build().post().uri("http://localhost:" + port + "/auth/login").body(Map.of("username", "admin", "password", "wrong"))
                .exchange((req, res) -> res.getStatusCode());
        String metrics = body(managementPort, "/actuator/prometheus");
        assertTrue(metrics.contains("auth_logins_total{application=\"backend\",outcome=\"failure\"}"), metrics.lines()
                .filter(l -> l.startsWith("auth_")).toList().toString());
        assertTrue(metrics.contains("jvm_memory_used_bytes"));
        assertTrue(metrics.contains("http_server_requests_seconds"));
        assertTrue(metrics.contains("hikaricp_connections"));
    }
}

package com.demo.backend.service;

import com.demo.backend.chat.ChatHistory;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The backend's side of the AI service's streamed reply, against a local stand-in for the AI service. */
class AiClientServiceStreamTest {
    private static final String KEY = "a-test-ai-api-key-of-at-least-32-chars";
    private HttpServer server;
    private volatile int status = 200;
    private volatile String body;
    private volatile String requestBody;
    private volatile String requestKey;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/reply/stream", exchange -> {
            requestKey = exchange.getRequestHeaders().getFirst("X-API-KEY");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiClientService client() {
        return new AiClientService("http://127.0.0.1:" + server.getAddress().getPort(), KEY, false, new SimpleMeterRegistry());
    }

    @Test
    void forwardsDeltasAndReturnsTheCompleteReply() {
        body = "{\"delta\":\"We're open \"}\n{\"delta\":\"9 to 5 ✓\"}\n{\"reply\":\"We're open 9 to 5 ✓\",\"done\":true}\n";
        List<String> deltas = new ArrayList<>();
        String reply = client().askAi(7L, "and weekends?", List.of(new ChatHistory.Exchange("hours?", "9 to 5.")), deltas::add);

        assertEquals("We're open 9 to 5 ✓", reply);
        assertEquals(List.of("We're open ", "9 to 5 ✓"), deltas);
        assertEquals(KEY, requestKey);
        assertTrue(requestBody.contains("\"history\":[{\"question\":\"hours?\",\"answer\":\"9 to 5.\"}]"), requestBody);
        assertTrue(requestBody.contains("\"message\":\"and weekends?\""), requestBody);
    }

    @Test
    void aStreamWithoutAFinalReplyOrAnErrorStatusIsUnavailable() {
        body = "{\"delta\":\"We're op\"}\n";
        assertEquals(AiClientService.UNAVAILABLE, client().askAi(7L, "hi", List.of(), d -> {}));
        status = 500;
        body = "{\"error\":\"boom\"}";
        assertEquals(AiClientService.UNAVAILABLE, client().askAi(7L, "hi", List.of(), d -> {}));
    }
}

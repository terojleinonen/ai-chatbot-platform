package com.demo.backend.service;

import com.demo.backend.chat.ChatHistory;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The backend's side of the AI service's streamed reply, against a local stand-in for the AI service. */
class AiClientServiceStreamTest {
    private static final String KEY = "a-test-ai-api-key-of-at-least-32-chars";
    private HttpServer server;
    private volatile int status = 200;
    private volatile String body;
    private volatile String requestBody;
    private volatile String requestKey;
    /** For the slow endpoint: completes with how writing after the client left went ("failed" when the pipe broke). */
    private final CompletableFuture<String> slowOutcome = new CompletableFuture<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/ai/reply/stream", exchange -> {
            requestKey = exchange.getRequestHeaders().getFirst("X-API-KEY");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // Streams a delta every 100ms for 10 seconds, like a long reply.
        server.createContext("/slow/ai/reply/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                for (int i = 0; i < 100; i++) {
                    out.write(("{\"delta\":\"w" + i + " \"}\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
                slowOutcome.complete("finished");
            } catch (IOException e) {
                slowOutcome.complete("failed");
            } catch (InterruptedException e) {
                slowOutcome.complete("interrupted");
            } finally {
                exchange.close();
            }
        });
        // Sends one delta, then nothing.
        server.createContext("/silent/ai/reply/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"delta\":\"We \"}\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiClientService client(String path, Duration readTimeout) {
        return new AiClientService("http://127.0.0.1:" + server.getAddress().getPort() + path, KEY, false, metrics, readTimeout);
    }

    private AiClientService client() {
        return client("", Duration.ofSeconds(5));
    }

    @Test
    void forwardsDeltasAndReturnsTheCompleteReply() {
        body = "{\"delta\":\"We're open \"}\n{\"delta\":\"9 to 5 ✓\"}\n{\"reply\":\"We're open 9 to 5 ✓\",\"done\":true}\n";
        List<String> deltas = new CopyOnWriteArrayList<>();
        String reply = client().askAi(7L, "and weekends?", List.of(new ChatHistory.Exchange("hours?", "9 to 5.")),
                deltas::add, new Cancellation());

        assertEquals("We're open 9 to 5 ✓", reply);
        assertEquals(List.of("We're open ", "9 to 5 ✓"), deltas);
        assertEquals(KEY, requestKey);
        assertTrue(requestBody.contains("\"history\":[{\"question\":\"hours?\",\"answer\":\"9 to 5.\"}]"), requestBody);
        assertTrue(requestBody.contains("\"message\":\"and weekends?\""), requestBody);
    }

    @Test
    void aStreamWithoutAFinalReplyOrAnErrorStatusIsUnavailable() {
        body = "{\"delta\":\"We're op\"}\n";
        assertEquals(AiClientService.UNAVAILABLE, client().askAi(7L, "hi", List.of(), d -> {}, new Cancellation()));
        status = 500;
        body = "{\"error\":\"boom\"}";
        assertEquals(AiClientService.UNAVAILABLE, client().askAi(7L, "hi", List.of(), d -> {}, new Cancellation()));
        assertEquals(2, metrics.timer("ai.requests", "operation", "reply", "outcome", "error").count());
    }

    @Test
    void cancellingDropsTheConnectionSoTheAiServiceStops() throws Exception {
        Cancellation cancellation = new Cancellation();
        CountDownLatch streaming = new CountDownLatch(3);
        List<String> deltas = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        CompletableFuture<String> reply = CompletableFuture.supplyAsync(() -> client("/slow", Duration.ofSeconds(5))
                .askAi(7L, "hi", List.of(), d -> { deltas.add(d); streaming.countDown(); }, cancellation));

        assertTrue(streaming.await(5, TimeUnit.SECONDS));
        cancellation.cancel();
        assertEquals(AiClientService.UNAVAILABLE, reply.get(2, TimeUnit.SECONDS));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "returned promptly");
        assertTrue(deltas.size() < 20, "stopped streaming: " + deltas.size());
        // The AI service notices on its next write that the backend hung up.
        assertEquals("failed", slowOutcome.get(5, TimeUnit.SECONDS));
        assertEquals(1, metrics.timer("ai.requests", "operation", "reply", "outcome", "cancelled").count());
    }

    @Test
    void cancellingBeforeTheCallStartsNeverReachesTheAiService() {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        body = "{\"reply\":\"x\",\"done\":true}\n";
        assertEquals(AiClientService.UNAVAILABLE, client().askAi(7L, "hi", List.of(), d -> {}, cancellation));
    }

    @Test
    void aStreamThatGoesSilentTimesOut() {
        List<String> deltas = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        assertEquals(AiClientService.UNAVAILABLE,
                client("/silent", Duration.ofMillis(500)).askAi(7L, "hi", List.of(), deltas::add, new Cancellation()));
        assertEquals(List.of("We "), deltas);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000, "timed out after ~500ms");
        assertEquals(1, metrics.timer("ai.requests", "operation", "reply", "outcome", "error").count());
    }
}

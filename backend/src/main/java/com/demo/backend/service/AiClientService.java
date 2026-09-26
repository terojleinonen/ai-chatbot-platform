package com.demo.backend.service;

import com.demo.backend.chat.ChatHistory;
import com.demo.backend.entity.Faq;
import com.demo.backend.security.SecretChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class AiClientService {
    private static final Logger log = LoggerFactory.getLogger(AiClientService.class);
    /** Reply shown when the AI service cannot be reached. */
    public static final String UNAVAILABLE = "Sorry, the assistant is unavailable right now. Please try again later.";

    /**
     * The read timeout is the longest wait for the start of a reply and then for each next part of it (the AI
     * service gives up on a Claude stream that is silent for 20s and then answers by keyword matching), so a hung AI
     * service can't tie up chat threads indefinitely.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Closes reply streams that go silent for longer than the read timeout. */
    private static final ScheduledExecutorService IDLE_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-reply-idle-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final RestTemplate restTemplate = restTemplate();
    /** For streamed replies: unlike RestTemplate's client, it can abort a response part-way without draining it. */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final String aiBaseUrl;
    private final String apiKey;
    private final MeterRegistry metrics;
    private final Duration readTimeout;

    @Autowired
    public AiClientService(@Value("${ai.base-url}") String aiBaseUrl,
                           @Value("${ai.api-key}") String apiKey,
                           @Value("${security.dev-mode:false}") boolean devMode,
                           MeterRegistry metrics) {
        this(aiBaseUrl, apiKey, devMode, metrics, READ_TIMEOUT);
    }

    AiClientService(String aiBaseUrl, String apiKey, boolean devMode, MeterRegistry metrics, Duration readTimeout) {
        this.metrics = metrics;
        this.aiBaseUrl = aiBaseUrl;
        this.apiKey = SecretChecks.requireStrong("AI_API_KEY", apiKey, devMode);
        this.readTimeout = readTimeout;
    }

    /**
     * Answers {@code message}, given the chat's earlier exchanges (oldest first) for context. The reply is streamed
     * from the AI service: text is passed to {@code onDelta} as it is written, and the returned complete reply
     * replaces it (they differ when generation failed part-way, including {@link #UNAVAILABLE}).
     * {@code cancellation} stops the call from another thread: the connection is dropped, which makes the AI service
     * stop generating, and {@link #UNAVAILABLE} is returned.
     */
    public String askAi(Long tenantId, String message, List<ChatHistory.Exchange> history, Consumer<String> onDelta,
                        Cancellation cancellation) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "message", message,
                "history", history
        );
        Timer.Sample timer = Timer.start(metrics);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(aiBaseUrl + "/ai/reply/stream"))
                    .timeout(readTimeout)   // until the response starts; then the idle watchdog takes over
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                    .build();
            CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            cancellation.onCancel(() -> pending.cancel(true));
            HttpResponse<InputStream> response = pending.get();
            try (InputStream in = response.body()) {
                cancellation.onCancel(() -> closeQuietly(in));
                if (response.statusCode() != 200) throw new IOException("AI service returned HTTP " + response.statusCode());
                String reply = readStream(in, onDelta, readTimeout);
                timer.stop(aiTimer("reply", "success"));
                return reply;
            }
        } catch (IOException | UncheckedIOException | ExecutionException | CancellationException e) {
            return failed(tenantId, timer, cancellation, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(tenantId, timer, cancellation, e);
        }
    }

    private String failed(Long tenantId, Timer.Sample timer, Cancellation cancellation, Exception e) {
        if (cancellation.isCancelled()) {
            timer.stop(aiTimer("reply", "cancelled"));
            log.debug("AI reply for tenant {} was stopped", tenantId);
        } else {
            timer.stop(aiTimer("reply", "error"));
            log.warn("AI reply failed for tenant {}: {}", tenantId, e.toString());
        }
        return UNAVAILABLE;
    }

    /**
     * Reads the AI service's newline-delimited JSON: {"delta"} lines, then {"reply", "done": true}. A stream silent
     * for longer than {@code idleTimeout} is closed, which ends the read with an exception.
     */
    static String readStream(InputStream in, Consumer<String> onDelta, Duration idleTimeout) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        while (true) {
            ScheduledFuture<?> idle = IDLE_WATCHDOG.schedule(() -> closeQuietly(in), idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            String line;
            try {
                line = reader.readLine();
            } finally {
                idle.cancel(false);
            }
            if (line == null) break;
            if (line.isBlank()) continue;
            JsonNode event = JSON.readTree(line);
            if (event.path("done").asBoolean()) return event.path("reply").asText();
            if (event.hasNonNull("delta")) onDelta.accept(event.get("delta").asText());
        }
        throw new IOException("AI reply stream ended without a final reply");
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Already closed or broken; either way the read ends.
        }
    }

    /** Sends the tenant's full FAQ set to the AI microservice, which replaces its copy and retrains. */
    public String train(Long tenantId, List<Faq> faqs) {
        List<Map<String, String>> body = faqs.stream()
                .map(f -> Map.of("question", f.getQuestion(), "answer", f.getAnswer()))
                .toList();
        Timer.Sample timer = Timer.start(metrics);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    aiBaseUrl + "/ai/train/" + tenantId, HttpMethod.POST, new HttpEntity<>(body, headers()), String.class);
            timer.stop(aiTimer("train", "success"));
            return resp.getBody();
        } catch (RestClientException e) {
            timer.stop(aiTimer("train", "error"));
            throw e;
        }
    }

    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }

    /** Latency and outcome of calls to the AI service (metric ai_requests_seconds{operation, outcome}). */
    private Timer aiTimer(String operation, String outcome) {
        return metrics.timer("ai.requests", "operation", operation, "outcome", outcome);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}

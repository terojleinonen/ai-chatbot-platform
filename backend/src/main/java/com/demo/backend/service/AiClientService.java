package com.demo.backend.service;

import com.demo.backend.chat.ChatHistory;
import com.demo.backend.entity.Faq;
import com.demo.backend.security.SecretChecks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class AiClientService {
    private static final Logger log = LoggerFactory.getLogger(AiClientService.class);
    /** Reply shown when the AI service cannot be reached. */
    public static final String UNAVAILABLE = "Sorry, the assistant is unavailable right now. Please try again later.";

    /**
     * Generous enough for a Claude reply including one retry (the AI service's AI_LLM_TIMEOUT is 20s per attempt),
     * short enough that a hung AI service can't tie up chat threads indefinitely.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestTemplate restTemplate = restTemplate();
    private final String aiBaseUrl;
    private final String apiKey;
    private final MeterRegistry metrics;

    public AiClientService(@Value("${ai.base-url}") String aiBaseUrl,
                           @Value("${ai.api-key}") String apiKey,
                           @Value("${security.dev-mode:false}") boolean devMode,
                           MeterRegistry metrics) {
        this.metrics = metrics;
        this.aiBaseUrl = aiBaseUrl;
        this.apiKey = SecretChecks.requireStrong("AI_API_KEY", apiKey, devMode);
    }

    /** Answers {@code message}, given the chat's earlier exchanges (oldest first) for context. */
    public String askAi(Long tenantId, String message, List<ChatHistory.Exchange> history) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "message", message,
                "history", history
        );
        Timer.Sample timer = Timer.start(metrics);
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    aiBaseUrl + "/ai/reply", HttpMethod.POST, new HttpEntity<>(body, headers()), Map.class);
            timer.stop(aiTimer("reply", "success"));
            Object reply = resp.getBody() != null ? resp.getBody().get("reply") : null;
            return reply != null ? reply.toString() : "No reply.";
        } catch (RestClientException e) {
            timer.stop(aiTimer("reply", "error"));
            log.warn("AI reply failed for tenant {}: {}", tenantId, e.getMessage());
            return UNAVAILABLE;
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

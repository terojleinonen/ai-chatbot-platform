package com.demo.backend.service;

import com.demo.backend.entity.Faq;
import com.demo.backend.security.SecretChecks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class AiClientService {
    private static final Logger log = LoggerFactory.getLogger(AiClientService.class);

    private final RestTemplate restTemplate = new RestTemplate();
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

    public String askAi(Long tenantId, String message) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "message", message
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
            return "Sorry, the assistant is unavailable right now. Please try again later.";
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

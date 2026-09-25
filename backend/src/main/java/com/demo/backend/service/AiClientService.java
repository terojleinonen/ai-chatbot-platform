package com.demo.backend.service;

import com.demo.backend.entity.Faq;
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

    public AiClientService(@Value("${ai.base-url}") String aiBaseUrl,
                           @Value("${ai.api-key}") String apiKey) {
        this.aiBaseUrl = aiBaseUrl;
        this.apiKey = apiKey;
    }

    public String askAi(Long tenantId, String message) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "message", message
        );
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    aiBaseUrl + "/ai/reply", HttpMethod.POST, new HttpEntity<>(body, headers()), Map.class);
            Object reply = resp.getBody() != null ? resp.getBody().get("reply") : null;
            return reply != null ? reply.toString() : "No reply.";
        } catch (RestClientException e) {
            log.warn("AI reply failed for tenant {}: {}", tenantId, e.getMessage());
            return "Sorry, the assistant is unavailable right now. Please try again later.";
        }
    }

    /** Sends the tenant's full FAQ set to the AI microservice, which replaces its copy and retrains. */
    public String train(Long tenantId, List<Faq> faqs) {
        List<Map<String, String>> body = faqs.stream()
                .map(f -> Map.of("question", f.getQuestion(), "answer", f.getAnswer()))
                .toList();
        ResponseEntity<String> resp = restTemplate.exchange(
                aiBaseUrl + "/ai/train/" + tenantId, HttpMethod.POST, new HttpEntity<>(body, headers()), String.class);
        return resp.getBody();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}

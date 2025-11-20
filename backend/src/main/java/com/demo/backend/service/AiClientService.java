package com.demo.backend.service;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class AiClientService {
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String AI_URL = "http://localhost:8081/ai/reply";
    private static final String API_KEY = "MY_INTERNAL_AI_KEY";

    public String askAi(Long tenantId, String message) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "message", message
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", API_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> resp = restTemplate.exchange(AI_URL, HttpMethod.POST, entity, Map.class);

        Object reply = resp.getBody() != null ? resp.getBody().get("reply") : null;
        return reply != null ? reply.toString() : "No reply.";
    }
}

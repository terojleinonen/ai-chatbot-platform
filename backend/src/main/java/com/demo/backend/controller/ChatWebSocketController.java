package com.demo.backend.controller;

import com.demo.backend.chat.ChatRateLimiter;
import com.demo.backend.entity.Tenant;
import com.demo.backend.service.AiClientService;
import com.demo.backend.service.TenantService;
import com.demo.backend.websocket.ChatHandshakeInterceptor;
import com.demo.backend.websocket.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Public chat used by the embeddable widget and the admin panel's test chat. Clients publish
 * {sessionId, widgetKey, content} to /app/chat.send and subscribe to /topic/replies/{sessionId}.
 * Every check below runs before the AI service is called.
 */
@Controller
public class ChatWebSocketController {
    static final String TOO_FAST = "You're sending messages too quickly. Please wait a moment and try again.";
    static final String EMPTY = "Please type a message.";
    static final String UNKNOWN_WIDGET = "This chat is not configured correctly.";
    static final String ORIGIN_NOT_ALLOWED = "This chat is not enabled on this website.";
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final AiClientService ai;
    private final TenantService tenants;
    private final ChatRateLimiter rateLimiter;
    private final SimpMessagingTemplate messaging;
    private final int maxMessageLength;
    /** The admin panel's origins may test every tenant's chat, whatever the tenant's allowed websites. */
    private final List<String> adminOrigins;

    public ChatWebSocketController(AiClientService ai, TenantService tenants, ChatRateLimiter rateLimiter,
                                   SimpMessagingTemplate messaging,
                                   @Value("${chat.max-message-length}") int maxMessageLength,
                                   @Value("${security.cors.allowed-origins}") List<String> adminOrigins) {
        this.ai = ai;
        this.tenants = tenants;
        this.rateLimiter = rateLimiter;
        this.messaging = messaging;
        this.maxMessageLength = maxMessageLength;
        this.adminOrigins = adminOrigins.stream().map(String::toLowerCase).toList();
    }

    @MessageMapping("/chat.send")
    public void handle(ChatMessage msg, SimpMessageHeaderAccessor headers) {
        // The session id becomes part of the reply topic, so only accept a plain random id.
        if (msg == null || msg.sessionId == null || !SESSION_ID.matcher(msg.sessionId).matches()) return;
        Map<String, Object> attributes = headers.getSessionAttributes() != null ? headers.getSessionAttributes() : Map.of();
        String ip = (String) attributes.getOrDefault(ChatHandshakeInterceptor.CLIENT_IP, "unknown");
        String origin = (String) attributes.get(ChatHandshakeInterceptor.ORIGIN);
        reply(msg.sessionId, answer(msg, ip, origin));
    }

    String answer(ChatMessage msg, String ip, String origin) {
        if (!rateLimiter.tryAcquire(ip)) return TOO_FAST;
        String content = msg.content == null ? "" : msg.content.trim();
        if (content.isEmpty()) return EMPTY;
        if (content.length() > maxMessageLength) {
            return "Your message is too long (at most " + maxMessageLength + " characters).";
        }
        Optional<Tenant> tenant = tenants.findByWidgetKey(msg.widgetKey);
        if (tenant.isEmpty()) return UNKNOWN_WIDGET;
        boolean adminPanel = origin != null && adminOrigins.contains(origin);
        if (!adminPanel && !tenant.get().allowsOrigin(origin)) return ORIGIN_NOT_ALLOWED;
        return ai.askAi(tenant.get().getId(), content);
    }

    private void reply(String sessionId, String text) {
        messaging.convertAndSend("/topic/replies/" + sessionId, Map.of("sessionId", sessionId, "reply", text));
    }
}

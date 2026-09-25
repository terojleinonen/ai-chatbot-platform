package com.demo.backend.controller;

import com.demo.backend.service.AiClientService;
import com.demo.backend.websocket.ChatMessage;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class ChatWebSocketController {
    private final AiClientService ai;
    private final SimpMessagingTemplate messaging;

    public ChatWebSocketController(AiClientService ai, SimpMessagingTemplate messaging) {
        this.ai = ai;
        this.messaging = messaging;
    }

    /**
     * Clients publish to /app/chat.send and subscribe to /topic/replies/{sessionId},
     * so each chat session only receives its own replies.
     */
    @MessageMapping("/chat.send")
    public void handle(ChatMessage msg) {
        if (msg.sessionId == null || msg.sessionId.isBlank()) return;
        String reply = (msg.tenantId == null || msg.content == null || msg.content.isBlank())
                ? "Missing tenant or message."
                : ai.askAi(msg.tenantId, msg.content);
        messaging.convertAndSend("/topic/replies/" + msg.sessionId, Map.of(
                "sessionId", msg.sessionId,
                "reply", reply
        ));
    }
}

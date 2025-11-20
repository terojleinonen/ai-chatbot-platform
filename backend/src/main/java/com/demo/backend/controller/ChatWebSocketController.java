package com.demo.backend.controller;

import com.demo.backend.service.AiClientService;
import com.demo.backend.websocket.ChatMessage;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class ChatWebSocketController {
    private final AiClientService ai;

    public ChatWebSocketController(AiClientService ai) {
        this.ai = ai;
    }

    @MessageMapping("/chat.send")
    @SendTo("/topic/replies")
    public Map<String, Object> handle(ChatMessage msg) {
        String reply = ai.askAi(msg.tenantId, msg.content);
        return Map.of(
                "sessionId", msg.sessionId,
                "reply", reply
        );
    }
}

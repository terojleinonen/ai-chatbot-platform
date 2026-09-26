package com.demo.backend.controller;

import com.demo.backend.chat.InMemoryChatRateLimiter;
import com.demo.backend.entity.Tenant;
import com.demo.backend.service.AiClientService;
import com.demo.backend.service.TenantService;
import com.demo.backend.websocket.ChatHandshakeInterceptor;
import com.demo.backend.websocket.ChatMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatWebSocketControllerTest {
    private final AiClientService ai = mock(AiClientService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private ChatWebSocketController controller;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        controller = new ChatWebSocketController(ai, tenants, new InMemoryChatRateLimiter(3, Duration.ofMinutes(1)),
                messaging, 20, List.of("https://admin.example.com"), metrics);
        tenant = new Tenant("Acme");
        tenant.setId(7L);
        tenant.rotateWidgetKey();
        when(tenants.findByWidgetKey(tenant.getWidgetKey())).thenReturn(Optional.of(tenant));
        when(ai.askAi(eq(7L), anyString())).thenReturn("AI answer");
    }

    private ChatMessage msg(String widgetKey, String content) {
        ChatMessage m = new ChatMessage();
        m.sessionId = "session-12345678";
        m.widgetKey = widgetKey;
        m.content = content;
        return m;
    }

    @Test
    void answersWithTheTenantResolvedFromTheWidgetKey() {
        assertEquals("AI answer", controller.answer(msg(tenant.getWidgetKey(), " hello "), "1.1.1.1", "https://shop.example"));
        verify(ai).askAi(7L, "hello");
    }

    @Test
    void unknownWidgetKeyNeverReachesTheAi() {
        assertEquals(ChatWebSocketController.UNKNOWN_WIDGET, controller.answer(msg("guess", "hi"), "1.1.1.1", null));
        verifyNoInteractions(ai);
    }

    @Test
    void allowedOriginsAreEnforcedExceptForTheAdminPanel() {
        tenant.setAllowedOriginList(List.of("https://shop.example"));
        assertEquals("AI answer", controller.answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", "https://shop.example"));
        assertEquals(ChatWebSocketController.ORIGIN_NOT_ALLOWED,
                controller.answer(msg(tenant.getWidgetKey(), "hi"), "2.2.2.2", "https://evil.example"));
        assertEquals(ChatWebSocketController.ORIGIN_NOT_ALLOWED,
                controller.answer(msg(tenant.getWidgetKey(), "hi"), "3.3.3.3", null));
        assertEquals("AI answer", controller.answer(msg(tenant.getWidgetKey(), "hi"), "4.4.4.4", "https://admin.example.com"));
        verify(ai, times(2)).askAi(anyLong(), anyString());
    }

    @Test
    void emptyAndOverlongMessagesAreRejected() {
        assertEquals(ChatWebSocketController.EMPTY, controller.answer(msg(tenant.getWidgetKey(), "  "), "1.1.1.1", null));
        assertEquals("Your message is too long (at most 20 characters).",
                controller.answer(msg(tenant.getWidgetKey(), "x".repeat(21)), "2.2.2.2", null));
        verifyNoInteractions(ai);
    }

    @Test
    void rateLimitIsCheckedFirst() {
        for (int i = 0; i < 3; i++) controller.answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null);
        assertEquals(ChatWebSocketController.TOO_FAST, controller.answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null));
        verify(ai, times(3)).askAi(anyLong(), anyString());
    }

    @Test
    void repliesOnTheSessionTopicUsingHandshakeAttributes() {
        tenant.setAllowedOriginList(List.of("https://shop.example"));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ChatHandshakeInterceptor.ORIGIN, "https://shop.example");
        attrs.put(ChatHandshakeInterceptor.CLIENT_IP, "5.5.5.5");
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionAttributes(attrs);

        controller.handle(msg(tenant.getWidgetKey(), "hi"), headers);
        verify(messaging).convertAndSend("/topic/replies/session-12345678",
                Map.of("sessionId", "session-12345678", "reply", "AI answer"));
    }

    @Test
    void countsOutcomes() {
        controller.answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null);
        controller.answer(msg("guess", "hi"), "2.2.2.2", null);
        controller.answer(msg("guess", "hi"), "3.3.3.3", null);
        assertEquals(1.0, metrics.counter("chat.messages", "outcome", "answered").count());
        assertEquals(2.0, metrics.counter("chat.messages", "outcome", "unknown_widget").count());
    }

    @Test
    void invalidSessionIdsAreDropped() {
        ChatMessage m = msg(tenant.getWidgetKey(), "hi");
        for (String bad : new String[]{null, "", "short", "../../topic/other", "x".repeat(65)}) {
            m.sessionId = bad;
            controller.handle(m, SimpMessageHeaderAccessor.create());
        }
        verifyNoInteractions(messaging, ai);
    }
}

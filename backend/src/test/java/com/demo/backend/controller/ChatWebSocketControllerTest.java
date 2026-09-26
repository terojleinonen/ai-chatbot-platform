package com.demo.backend.controller;

import com.demo.backend.chat.ChatHistory;
import com.demo.backend.chat.InMemoryChatHistory;
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
    private final ChatHistory history = new InMemoryChatHistory(10, Duration.ofMinutes(30));
    private ChatWebSocketController controller;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        controller = new ChatWebSocketController(ai, tenants, new InMemoryChatRateLimiter(3, Duration.ofMinutes(1)),
                history, messaging, 20, List.of("https://admin.example.com"), metrics);
        tenant = new Tenant("Acme");
        tenant.setId(7L);
        tenant.rotateWidgetKey();
        when(tenants.findByWidgetKey(tenant.getWidgetKey())).thenReturn(Optional.of(tenant));
        when(ai.askAi(eq(7L), anyString(), anyList(), any())).thenReturn("AI answer");
    }

    private String answer(ChatMessage m, String ip, String origin) {
        return controller.answer(m, ip, origin, delta -> {});
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
        assertEquals("AI answer", answer(msg(tenant.getWidgetKey(), " hello "), "1.1.1.1", "https://shop.example"));
        verify(ai).askAi(eq(7L), eq("hello"), eq(List.of()), any());
    }

    @Test
    void unknownWidgetKeyNeverReachesTheAi() {
        assertEquals(ChatWebSocketController.UNKNOWN_WIDGET, answer(msg("guess", "hi"), "1.1.1.1", null));
        verifyNoInteractions(ai);
    }

    @Test
    void allowedOriginsAreEnforcedExceptForTheAdminPanel() {
        tenant.setAllowedOriginList(List.of("https://shop.example"));
        assertEquals("AI answer", answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", "https://shop.example"));
        assertEquals(ChatWebSocketController.ORIGIN_NOT_ALLOWED,
                answer(msg(tenant.getWidgetKey(), "hi"), "2.2.2.2", "https://evil.example"));
        assertEquals(ChatWebSocketController.ORIGIN_NOT_ALLOWED,
                answer(msg(tenant.getWidgetKey(), "hi"), "3.3.3.3", null));
        assertEquals("AI answer", answer(msg(tenant.getWidgetKey(), "hi"), "4.4.4.4", "https://admin.example.com"));
        verify(ai, times(2)).askAi(anyLong(), anyString(), anyList(), any());
    }

    @Test
    void emptyAndOverlongMessagesAreRejected() {
        assertEquals(ChatWebSocketController.EMPTY, answer(msg(tenant.getWidgetKey(), "  "), "1.1.1.1", null));
        assertEquals("Your message is too long (at most 20 characters).",
                answer(msg(tenant.getWidgetKey(), "x".repeat(21)), "2.2.2.2", null));
        verifyNoInteractions(ai);
    }

    @Test
    void rateLimitIsCheckedFirst() {
        for (int i = 0; i < 3; i++) answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null);
        assertEquals(ChatWebSocketController.TOO_FAST, answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null));
        verify(ai, times(3)).askAi(anyLong(), anyString(), anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamsRepliesOnTheSessionTopicUsingHandshakeAttributes() {
        tenant.setAllowedOriginList(List.of("https://shop.example"));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ChatHandshakeInterceptor.ORIGIN, "https://shop.example");
        attrs.put(ChatHandshakeInterceptor.CLIENT_IP, "5.5.5.5");
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionAttributes(attrs);

        when(ai.askAi(eq(7L), eq("hi"), anyList(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(3);
            onDelta.accept("Hello ");
            onDelta.accept("there");
            return "Hello there!";
        });
        controller.handle(msg(tenant.getWidgetKey(), "hi"), headers);

        // Streamed deltas, then the complete reply, all tagged with the same reply id.
        var payloads = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(messaging, times(3)).convertAndSend(eq("/topic/replies/session-12345678"), payloads.capture());
        List<Map<String, Object>> sent = payloads.getAllValues().stream().map(p -> (Map<String, Object>) p).toList();
        String replyId = (String) sent.get(0).get("replyId");
        assertEquals(Map.of("sessionId", "session-12345678", "replyId", replyId, "delta", "Hello "), sent.get(0));
        assertEquals(Map.of("sessionId", "session-12345678", "replyId", replyId, "delta", "there"), sent.get(1));
        assertEquals(Map.of("sessionId", "session-12345678", "replyId", replyId, "reply", "Hello there!", "done", true),
                sent.get(2));

        // Rejections (here: unknown widget key) are a single final message.
        controller.handle(msg("guess", "hi"), headers);
        verify(messaging, times(4)).convertAndSend(eq("/topic/replies/session-12345678"), payloads.capture());
        Map<String, Object> rejected = (Map<String, Object>) payloads.getValue();
        assertEquals(ChatWebSocketController.UNKNOWN_WIDGET, rejected.get("reply"));
        assertEquals(true, rejected.get("done"));
    }

    @Test
    void countsOutcomes() {
        answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null);
        answer(msg("guess", "hi"), "2.2.2.2", null);
        answer(msg("guess", "hi"), "3.3.3.3", null);
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

    @Test
    void earlierExchangesOfTheSessionAreSentWithEachQuestion() {
        when(ai.askAi(eq(7L), eq("opening hours?"), eq(List.of()), any())).thenReturn("9 to 5.");
        when(ai.askAi(eq(7L), eq("and on weekends?"), eq(List.of(new ChatHistory.Exchange("opening hours?", "9 to 5."))), any()))
                .thenReturn("Closed on weekends.");
        assertEquals("9 to 5.", answer(msg(tenant.getWidgetKey(), "opening hours?"), "1.1.1.1", null));
        assertEquals("Closed on weekends.", answer(msg(tenant.getWidgetKey(), "and on weekends?"), "1.1.1.1", null));

        // Another session of the same tenant starts without history.
        ChatMessage other = msg(tenant.getWidgetKey(), "hi");
        other.sessionId = "session-other-1";
        answer(other, "2.2.2.2", null);
        verify(ai).askAi(eq(7L), eq("hi"), eq(List.of()), any());
    }

    @Test
    void failedAiCallsAreNotRemembered() {
        when(ai.askAi(eq(7L), eq("opening hours?"), eq(List.of()), any())).thenReturn(AiClientService.UNAVAILABLE);
        answer(msg(tenant.getWidgetKey(), "opening hours?"), "1.1.1.1", null);
        answer(msg(tenant.getWidgetKey(), "hi"), "1.1.1.1", null);
        verify(ai).askAi(eq(7L), eq("hi"), eq(List.of()), any());
    }
}

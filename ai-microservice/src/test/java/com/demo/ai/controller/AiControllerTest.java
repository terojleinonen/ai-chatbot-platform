package com.demo.ai.controller;

import com.demo.ai.dto.AiRequest;
import com.demo.ai.dto.TrainFaqDto;
import com.demo.ai.service.MultiTenantAiService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiControllerTest {
    private final MultiTenantAiService service = mock(MultiTenantAiService.class);
    private final AiController controller = new AiController(service);

    private static AiRequest req(Long tenant, String message) {
        AiRequest r = new AiRequest();
        r.setTenantId(tenant);
        r.setMessage(message);
        return r;
    }

    @Test
    void rejectsInvalidReplyRequests() {
        assertThrows(ResponseStatusException.class, () -> controller.reply(req(null, "hi")));
        assertThrows(ResponseStatusException.class, () -> controller.reply(req(1L, " ")));
        assertThrows(ResponseStatusException.class, () -> controller.reply(req(1L, "x".repeat(4001))));
        verifyNoInteractions(service);
        when(service.reply(1L, "hi")).thenReturn("hello");
        assertEquals("hello", controller.reply(req(1L, "hi")).getReply());
    }

    @Test
    void rejectsOversizedTrainingData() {
        TrainFaqDto big = new TrainFaqDto();
        big.setQuestion("q".repeat(1001));
        big.setAnswer("a");
        assertThrows(ResponseStatusException.class, () -> controller.train(1L, List.of(big)));
        verifyNoInteractions(service);
    }
}

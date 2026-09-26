package com.demo.ai.service;

import com.anthropic.errors.AnthropicIoException;
import com.demo.ai.dto.ChatExchange;
import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.llm.ClaudeResponder;
import com.demo.ai.llm.LlmUnusableReplyException;
import com.demo.ai.model.TenantModel;
import com.demo.ai.repository.TenantFaqRepository;
import com.demo.ai.repository.TenantModelVersionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MultiTenantAiServiceTest {
    private final TenantFaqRepository faqs = mock(TenantFaqRepository.class);
    private final TenantModelVersionRepository versions = mock(TenantModelVersionRepository.class);
    private final ClaudeResponder llm = mock(ClaudeResponder.class);   // disabled unless a test enables it
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MultiTenantAiService service = new MultiTenantAiService(faqs, versions, llm, metrics);

    private static List<TenantFaqEntity> faq(String q, String a) {
        return List.of(new TenantFaqEntity(1L, q, a));
    }

    @Test
    void cachesTheModelWhileTheVersionIsUnchanged() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(3L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        assertEquals("9 to 5.", service.reply(1L, "opening hours", List.of()));
        assertEquals("9 to 5.", service.reply(1L, "opening hours", List.of()));
        verify(faqs, times(1)).findByTenantId(1L);
    }

    @Test
    void reloadsWhenAnotherInstanceRetrained() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(3L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        assertEquals("9 to 5.", service.reply(1L, "opening hours", List.of()));

        // Another instance retrained: new FAQs, version 4.
        when(versions.findVersion(1L)).thenReturn(Optional.of(4L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "10 to 6."));
        assertEquals("10 to 6.", service.reply(1L, "opening hours", List.of()));
        verify(faqs, times(2)).findByTenantId(1L);
    }

    @Test
    void tenantsWithoutAVersionRowStillWork() {   // data trained before versioning existed
        when(versions.findVersion(1L)).thenReturn(Optional.empty());
        when(faqs.findByTenantId(1L)).thenReturn(faq("Do you ship abroad?", "Yes."));
        assertEquals("Yes.", service.reply(1L, "ship abroad", List.of()));
        when(faqs.findByTenantId(2L)).thenReturn(List.of());
        when(versions.findVersion(2L)).thenReturn(Optional.empty());
        assertEquals("This tenant has no training data yet.", service.reply(2L, "hi", List.of()));
    }

    @Test
    void answersWithClaudeWhenConfigured() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(1L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        when(llm.enabled()).thenReturn(true);
        when(llm.answer(any(), eq("when can I visit?"), any())).thenReturn(new ClaudeResponder.Reply("We're open 9 to 5.", true));
        when(llm.answer(any(), eq("who is your CEO?"), any())).thenReturn(new ClaudeResponder.Reply(TenantModel.NO_MATCH, false));

        assertEquals("We're open 9 to 5.", service.reply(1L, "when can I visit?", List.of()));
        assertEquals(TenantModel.NO_MATCH, service.reply(1L, "who is your CEO?", List.of()));
        assertEquals(1, metrics.counter("ai.replies", "result", "answered", "source", "llm").count());
        assertEquals(1, metrics.counter("ai.replies", "result", "no_match", "source", "llm").count());
    }

    @Test
    void fallsBackToKeywordMatchingWhenClaudeFails() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(1L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        when(llm.enabled()).thenReturn(true);
        when(llm.answer(any(), any(), any())).thenThrow(new AnthropicIoException("connection refused"));
        assertEquals("9 to 5.", service.reply(1L, "opening hours", List.of()));

        reset(llm);
        when(llm.enabled()).thenReturn(true);
        when(llm.answer(any(), any(), any())).thenThrow(new LlmUnusableReplyException("refused"));
        assertEquals("9 to 5.", service.reply(1L, "opening hours", List.of()));
        assertEquals(2, metrics.counter("ai.replies", "result", "answered", "source", "keyword").count());
    }

    @Test
    void passesTheChatHistoryToClaude() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(1L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        when(llm.enabled()).thenReturn(true);
        List<ChatExchange> history = List.of(new ChatExchange("opening hours?", "9 to 5."));
        when(llm.answer(any(), eq("and on weekends?"), eq(history))).thenReturn(new ClaudeResponder.Reply("Closed.", true));
        assertEquals("Closed.", service.reply(1L, "and on weekends?", history));
    }
}

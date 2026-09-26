package com.demo.ai.service;

import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.repository.TenantFaqRepository;
import com.demo.ai.repository.TenantModelVersionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MultiTenantAiServiceTest {
    private final TenantFaqRepository faqs = mock(TenantFaqRepository.class);
    private final TenantModelVersionRepository versions = mock(TenantModelVersionRepository.class);
    private final MultiTenantAiService service = new MultiTenantAiService(faqs, versions, new SimpleMeterRegistry());

    private static List<TenantFaqEntity> faq(String q, String a) {
        return List.of(new TenantFaqEntity(1L, q, a));
    }

    @Test
    void cachesTheModelWhileTheVersionIsUnchanged() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(3L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        assertEquals("9 to 5.", service.reply(1L, "opening hours"));
        assertEquals("9 to 5.", service.reply(1L, "opening hours"));
        verify(faqs, times(1)).findByTenantId(1L);
    }

    @Test
    void reloadsWhenAnotherInstanceRetrained() {
        when(versions.findVersion(1L)).thenReturn(Optional.of(3L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "9 to 5."));
        assertEquals("9 to 5.", service.reply(1L, "opening hours"));

        // Another instance retrained: new FAQs, version 4.
        when(versions.findVersion(1L)).thenReturn(Optional.of(4L));
        when(faqs.findByTenantId(1L)).thenReturn(faq("What are your opening hours?", "10 to 6."));
        assertEquals("10 to 6.", service.reply(1L, "opening hours"));
        verify(faqs, times(2)).findByTenantId(1L);
    }

    @Test
    void tenantsWithoutAVersionRowStillWork() {   // data trained before versioning existed
        when(versions.findVersion(1L)).thenReturn(Optional.empty());
        when(faqs.findByTenantId(1L)).thenReturn(faq("Do you ship abroad?", "Yes."));
        assertEquals("Yes.", service.reply(1L, "ship abroad"));
        when(faqs.findByTenantId(2L)).thenReturn(List.of());
        when(versions.findVersion(2L)).thenReturn(Optional.empty());
        assertEquals("This tenant has no training data yet.", service.reply(2L, "hi"));
    }
}

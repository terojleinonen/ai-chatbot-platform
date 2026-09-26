package com.demo.ai.service;

import com.anthropic.errors.AnthropicException;
import com.demo.ai.dto.ChatExchange;
import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.entity.TenantModelVersion;
import com.demo.ai.llm.ClaudeResponder;
import com.demo.ai.llm.LlmUnusableReplyException;
import com.demo.ai.model.TenantModel;
import com.demo.ai.repository.TenantFaqRepository;
import com.demo.ai.repository.TenantModelVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Keeps one in-memory model per tenant. Every retrain bumps the tenant's version in the database, and each
 * reply checks it, so when several AI instances run, one that did not receive a retrain reloads the tenant's
 * FAQs instead of answering from stale data.
 * <p>
 * Questions are answered by Claude from the tenant's FAQs, with the chat's earlier exchanges for context. Without an API key, or when the Claude call fails,
 * the answer comes from keyword matching (the FAQ whose question is most similar to the latest message) so the
 * chat keeps working.
 */
@Service
public class MultiTenantAiService {
    private static final Logger log = LoggerFactory.getLogger(MultiTenantAiService.class);

    /** A model together with the database version its FAQs were read at (0 = no version row yet). */
    private record Cached(TenantModel model, long version) {}

    private final TenantFaqRepository faqRepo;
    private final TenantModelVersionRepository versions;
    private final ClaudeResponder llm;
    private final MeterRegistry metrics;
    private final Map<Long, Cached> models = new ConcurrentHashMap<>();

    public MultiTenantAiService(TenantFaqRepository faqRepo, TenantModelVersionRepository versions,
                               ClaudeResponder llm, MeterRegistry metrics) {
        this.faqRepo = faqRepo;
        this.llm = llm;
        this.versions = versions;
        this.metrics = metrics;
        metrics.gaugeMapSize("ai.tenant.models", List.of(), models);
    }

    public void loadAllTenants() {
        // Versions first: FAQs read afterwards are at least that new, so a stale model is never labelled current.
        Map<Long, Long> current = new HashMap<>();
        for (TenantModelVersion v : versions.findAll()) current.put(v.getTenantId(), v.getVersion());
        Map<Long, List<TenantFaqEntity>> grouped = new HashMap<>();
        for (TenantFaqEntity faq : faqRepo.findAll()) {
            grouped.computeIfAbsent(faq.getTenantId(), k -> new ArrayList<>()).add(faq);
        }
        grouped.forEach((tenantId, faqs) ->
                models.put(tenantId, new Cached(new TenantModel(faqs), current.getOrDefault(tenantId, 0L))));
    }

    @Transactional
    public void replaceFaqsAndTrain(Long tenantId, List<TenantFaqEntity> faqs) {
        faqRepo.deleteByTenantId(tenantId);
        faqRepo.saveAll(faqs);
        versions.bump(tenantId);
        long version = versions.findVersion(tenantId).orElse(0L);
        models.put(tenantId, new Cached(new TenantModel(faqRepo.findByTenantId(tenantId)), version));
    }

    /** Answers {@code message}; {@code history} (earlier exchanges of the chat, oldest first) gives Claude context. */
    public String reply(Long tenantId, String message, List<ChatExchange> history) {
        return reply(tenantId, message, history, text -> {});
    }

    /**
     * Like {@link #reply(Long, String, List)}, passing Claude's text to {@code onText} as it is written. The returned
     * reply is the complete answer and replaces any streamed text (they differ when Claude fails part-way and keyword
     * matching answers instead).
     */
    public String reply(Long tenantId, String message, List<ChatExchange> history, Consumer<String> onText) {
        if (tenantId == null) return "Missing tenant id.";
        long current = versions.findVersion(tenantId).orElse(0L);
        Cached cached = models.get(tenantId);
        if (cached == null || cached.version() != current) {
            List<TenantFaqEntity> faqs = faqRepo.findByTenantId(tenantId);
            if (faqs.isEmpty()) {
                models.remove(tenantId);
                return count("no_data", "none", "This tenant has no training data yet.");
            }
            cached = new Cached(new TenantModel(faqs), current);
            models.put(tenantId, cached);
        }
        if (llm.enabled()) {
            try {
                ClaudeResponder.Reply reply = llm.answer(cached.model(), message, history, onText);
                return count(reply.answered() ? "answered" : "no_match", "llm", reply.text());
            } catch (AnthropicException | LlmUnusableReplyException e) {
                log.warn("Claude reply failed for tenant {}, using keyword matching: {}", tenantId, e.toString());
            }
        }
        String answer = cached.model().getBestAnswer(message);
        String result = TenantModel.NO_MATCH.equals(answer) ? "no_match" : TenantModel.NO_DATA.equals(answer) ? "no_data" : "answered";
        return count(result, "keyword", answer);
    }

    /**
     * Counts replies by result and by what produced them (metric ai_replies_total{result=answered|no_match|no_data,
     * source=llm|keyword|none}); source=keyword while Claude is configured means Claude calls are failing.
     */
    private String count(String result, String source, String answer) {
        metrics.counter("ai.replies", "result", result, "source", source).increment();
        return answer;
    }
}

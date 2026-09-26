package com.demo.ai.service;

import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.model.TenantModel;
import com.demo.ai.repository.TenantFaqRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MultiTenantAiService {
    private final TenantFaqRepository faqRepo;
    private final Map<Long, TenantModel> models = new ConcurrentHashMap<>();

    private final MeterRegistry metrics;

    public MultiTenantAiService(TenantFaqRepository faqRepo, MeterRegistry metrics) {
        this.faqRepo = faqRepo;
        this.metrics = metrics;
        metrics.gaugeMapSize("ai.tenant.models", java.util.List.of(), models);
    }

    public void loadAllTenants() {
        List<TenantFaqEntity> all = faqRepo.findAll();
        Map<Long, List<TenantFaqEntity>> grouped = new HashMap<>();
        for (TenantFaqEntity faq : all) {
            grouped.computeIfAbsent(faq.getTenantId(), k -> new ArrayList<>()).add(faq);
        }
        grouped.forEach((tenantId, faqs) -> models.put(tenantId, new TenantModel(faqs)));
    }

    @Transactional
    public void replaceFaqsAndTrain(Long tenantId, List<TenantFaqEntity> faqs) {
        faqRepo.deleteByTenantId(tenantId);
        faqRepo.saveAll(faqs);
        List<TenantFaqEntity> fresh = faqRepo.findByTenantId(tenantId);
        models.put(tenantId, new TenantModel(fresh));
    }

    public String reply(Long tenantId, String message) {
        if (tenantId == null) return "Missing tenant id.";
        TenantModel model = models.get(tenantId);
        if (model == null) {
            List<TenantFaqEntity> faqs = faqRepo.findByTenantId(tenantId);
            if (faqs.isEmpty()) return count("no_data", "This tenant has no training data yet.");
            model = new TenantModel(faqs);
            models.put(tenantId, model);
        }
        String answer = model.getBestAnswer(message);
        String result = TenantModel.NO_MATCH.equals(answer) ? "no_match" : TenantModel.NO_DATA.equals(answer) ? "no_data" : "answered";
        return count(result, answer);
    }

    /** Counts replies by result (metric ai_replies_total{result=answered|no_match|no_data}). */
    private String count(String result, String answer) {
        metrics.counter("ai.replies", "result", result).increment();
        return answer;
    }
}

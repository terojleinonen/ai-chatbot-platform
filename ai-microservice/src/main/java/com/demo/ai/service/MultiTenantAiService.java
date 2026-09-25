package com.demo.ai.service;

import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.model.TenantModel;
import com.demo.ai.repository.TenantFaqRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MultiTenantAiService {
    private final TenantFaqRepository faqRepo;
    private final Map<Long, TenantModel> models = new ConcurrentHashMap<>();

    public MultiTenantAiService(TenantFaqRepository faqRepo) {
        this.faqRepo = faqRepo;
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
            if (faqs.isEmpty()) return "This tenant has no training data yet.";
            model = new TenantModel(faqs);
            models.put(tenantId, model);
        }
        return model.getBestAnswer(message);
    }
}

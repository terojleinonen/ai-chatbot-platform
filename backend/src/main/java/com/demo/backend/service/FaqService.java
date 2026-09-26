package com.demo.backend.service;

import com.demo.backend.entity.Faq;
import com.demo.backend.repository.FaqRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

@Service
public class FaqService {
    private static final Logger log = LoggerFactory.getLogger(FaqService.class);

    private final FaqRepository repo;
    private final AiClientService ai;

    public FaqService(FaqRepository repo, AiClientService ai) {
        this.repo = repo;
        this.ai = ai;
    }

    /** Limits match the database columns, so bad input gets a clear 400 instead of a database error. */
    public static final int MAX_QUESTION_LENGTH = 1000;
    public static final int MAX_ANSWER_LENGTH = 3000;
    public static final int MAX_IMPORT_SIZE = 5000;

    static void validate(Faq faq) {
        String question = faq.getQuestion() == null ? "" : faq.getQuestion().trim();
        String answer = faq.getAnswer() == null ? "" : faq.getAnswer().trim();
        if (question.isEmpty() || answer.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question and answer are required");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Question is too long (at most " + MAX_QUESTION_LENGTH + " characters)");
        }
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Answer is too long (at most " + MAX_ANSWER_LENGTH + " characters)");
        }
        faq.setQuestion(question);
        faq.setAnswer(answer);
    }

    public Faq create(Faq faq) {
        validate(faq);
        Faq saved = repo.save(faq);
        syncQuietly(saved.getTenantId());
        return saved;
    }

    public List<Faq> list(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public Optional<Faq> findById(Long id) { return repo.findById(id); }

    public void delete(Long id) {
        repo.findById(id).ifPresent(faq -> {
            repo.delete(faq);
            syncQuietly(faq.getTenantId());
        });
    }

    /** Replaces all FAQs of a tenant (used by CSV import) and retrains the AI. */
    @Transactional
    public List<Faq> replaceAll(Long tenantId, List<Faq> faqs) {
        if (faqs == null) faqs = List.of();
        if (faqs.size() > MAX_IMPORT_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many FAQs in one import (at most " + MAX_IMPORT_SIZE + ")");
        }
        for (int i = 0; i < faqs.size(); i++) {
            try {
                validate(faqs.get(i));
            } catch (ResponseStatusException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Row " + (i + 1) + ": " + e.getReason());
            }
        }
        repo.deleteByTenantId(tenantId);
        faqs.forEach(f -> {
            f.setId(null);
            f.setTenantId(tenantId);
        });
        List<Faq> saved = repo.saveAll(faqs);
        syncQuietly(tenantId);
        return saved;
    }

    /** Pushes the tenant's current FAQs to the AI microservice. Throws if the AI service is unreachable. */
    public String train(Long tenantId) {
        return ai.train(tenantId, repo.findByTenantId(tenantId));
    }

    private void syncQuietly(Long tenantId) {
        if (tenantId == null) return;
        try {
            train(tenantId);
        } catch (RestClientException e) {
            log.warn("Could not retrain AI for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}

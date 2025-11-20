package com.demo.backend.service;

import com.demo.backend.entity.Faq;
import com.demo.backend.repository.FaqRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class FaqService {
    private final FaqRepository repo;

    public FaqService(FaqRepository repo) {
        this.repo = repo;
    }

    public Faq create(Faq faq) { return repo.save(faq); }

    public List<Faq> list(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public Optional<Faq> findById(Long id) { return repo.findById(id); }

    public void delete(Long id) { repo.deleteById(id); }
}

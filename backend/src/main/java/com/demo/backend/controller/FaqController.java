package com.demo.backend.controller;

import com.demo.backend.entity.Faq;
import com.demo.backend.security.AccessControl;
import com.demo.backend.service.FaqService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/faq")
public class FaqController {
    private final FaqService service;
    private final AccessControl access;

    public FaqController(FaqService service, AccessControl access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/create")
    public Faq create(@RequestBody Faq faq) {
        access.requireTenantAccess(faq.getTenantId());
        faq.setId(null); // never let a client overwrite an existing FAQ (possibly another tenant's)
        return service.create(faq);
    }

    @GetMapping("/list/{tenantId}")
    public List<Faq> list(@PathVariable Long tenantId) {
        access.requireTenantAccess(tenantId);
        return service.list(tenantId);
    }

    @PutMapping("/{id}")
    public Faq update(@PathVariable Long id, @RequestBody Faq updates) {
        Faq faq = findAccessible(id);
        faq.setQuestion(updates.getQuestion());
        faq.setAnswer(updates.getAnswer());
        return service.create(faq);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        findAccessible(id);
        service.delete(id);
    }

    @PostMapping("/import/{tenantId}")
    public List<Faq> importFaqs(@PathVariable Long tenantId, @RequestBody List<Faq> faqs) {
        access.requireTenantAccess(tenantId);
        return service.replaceAll(tenantId, faqs);
    }

    @PostMapping("/train/{tenantId}")
    public Map<String, String> train(@PathVariable Long tenantId) {
        access.requireTenantAccess(tenantId);
        try {
            return Map.of("message", service.train(tenantId));
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service unavailable: " + e.getMessage());
        }
    }

    /** Loads an FAQ the current user may manage; 404 if missing, 403 if it belongs to another tenant. */
    private Faq findAccessible(Long id) {
        Faq faq = service.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FAQ not found"));
        access.requireTenantAccess(faq.getTenantId());
        return faq;
    }
}

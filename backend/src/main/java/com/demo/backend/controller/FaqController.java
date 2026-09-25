package com.demo.backend.controller;

import com.demo.backend.entity.Faq;
import com.demo.backend.service.FaqService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/faq")
@CrossOrigin
public class FaqController {
    private final FaqService service;

    public FaqController(FaqService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public Faq create(@RequestBody Faq faq) {
        return service.create(faq);
    }

    @GetMapping("/list/{tenantId}")
    public List<Faq> list(@PathVariable Long tenantId) {
        return service.list(tenantId);
    }

    @PutMapping("/{id}")
    public Faq update(@PathVariable Long id, @RequestBody Faq updates) {
        return service.findById(id).map(f -> {
            f.setQuestion(updates.getQuestion());
            f.setAnswer(updates.getAnswer());
            return service.create(f);
        }).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FAQ not found"));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/import/{tenantId}")
    public List<Faq> importFaqs(@PathVariable Long tenantId, @RequestBody List<Faq> faqs) {
        return service.replaceAll(tenantId, faqs);
    }

    @PostMapping("/train/{tenantId}")
    public Map<String, String> train(@PathVariable Long tenantId) {
        try {
            return Map.of("message", service.train(tenantId));
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service unavailable: " + e.getMessage());
        }
    }
}

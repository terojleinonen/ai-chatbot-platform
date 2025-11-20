package com.demo.backend.controller;

import com.demo.backend.entity.Faq;
import com.demo.backend.service.FaqService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        }).orElseThrow();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}

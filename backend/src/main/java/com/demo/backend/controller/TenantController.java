package com.demo.backend.controller;

import com.demo.backend.entity.Tenant;
import com.demo.backend.service.TenantService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tenants")
public class TenantController {
    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public Tenant create(@RequestBody Tenant tenant) {
        return service.create(tenant);
    }

    @GetMapping("/list")
    public List<Tenant> list() {
        return service.list();
    }
}

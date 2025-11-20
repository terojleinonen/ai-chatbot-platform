package com.demo.backend.service;

import com.demo.backend.entity.Tenant;
import com.demo.backend.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantService {
    private final TenantRepository repo;

    public TenantService(TenantRepository repo) {
        this.repo = repo;
    }

    public Tenant create(Tenant t) { return repo.save(t); }
    public List<Tenant> list() { return repo.findAll(); }
}

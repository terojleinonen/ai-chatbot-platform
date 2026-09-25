package com.demo.backend.controller;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.entity.Tenant;
import com.demo.backend.security.AccessControl;
import com.demo.backend.service.TenantService;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/tenants")
public class TenantController {
    private final TenantService service;
    private final AccessControl access;

    public TenantController(TenantService service, AccessControl access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/create")
    public Tenant create(@RequestBody Tenant tenant) {
        access.requireSuperAdmin();
        tenant.setId(null); // never let a client overwrite an existing tenant
        return service.create(tenant);
    }

    /** Super admins see every tenant; tenant admins only their assigned ones. */
    @GetMapping("/list")
    public List<Tenant> list() {
        AdminUser user = access.currentUser();
        if (user.isSuperAdmin()) return service.list();
        return user.getTenants().stream().sorted(Comparator.comparing(Tenant::getId)).toList();
    }
}

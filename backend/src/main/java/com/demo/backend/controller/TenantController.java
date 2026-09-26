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

    public record CreateTenantRequest(String name) {}
    public record TenantSettingsRequest(List<String> allowedOrigins) {}

    /** Only the name is taken from the request; id and widget key are always generated. */
    @PostMapping("/create")
    public Tenant create(@RequestBody CreateTenantRequest req) {
        access.requireSuperAdmin();
        return service.create(req.name());
    }

    /** Super admins see every tenant; tenant admins only their assigned ones. */
    @GetMapping("/list")
    public List<Tenant> list() {
        AdminUser user = access.currentUser();
        if (user.isSuperAdmin()) return service.list();
        return user.getTenants().stream().sorted(Comparator.comparing(Tenant::getId)).toList();
    }

    /** Sets which websites may use the tenant's chat widget (empty list = any website). */
    @PutMapping("/{id}/settings")
    public Tenant updateSettings(@PathVariable Long id, @RequestBody TenantSettingsRequest req) {
        access.requireTenantAccess(id);
        return service.updateAllowedOrigins(id, req.allowedOrigins());
    }

    /** Issues a new widget key; widgets embedded with the old key stop working immediately. */
    @PostMapping("/{id}/widget-key")
    public Tenant rotateWidgetKey(@PathVariable Long id) {
        access.requireTenantAccess(id);
        return service.rotateWidgetKey(id);
    }
}

package com.demo.backend.controller;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.entity.Tenant;
import com.demo.backend.security.AccessControl;
import com.demo.backend.service.TenantService;
import com.demo.backend.web.PageResponse;
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

    public record TenantOption(Long id, String name, String widgetKey) {}

    /** One page of tenants (newest first). Super admins see every tenant; tenant admins only their assigned ones. */
    @GetMapping("/list")
    public PageResponse<Tenant> list(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String q) {
        AdminUser user = access.currentUser();
        List<Long> ids = user.getTenants().stream().map(Tenant::getId).toList();
        return PageResponse.of(service.search(user.isSuperAdmin(), ids, PageResponse.query(q),
                PageResponse.request(page, size)), t -> t);
    }

    /** Every tenant the user can access, as {id, name, widgetKey}, sorted by name (for tenant pickers). */
    @GetMapping("/options")
    public List<TenantOption> options() {
        AdminUser user = access.currentUser();
        List<Tenant> tenants = user.isSuperAdmin() ? service.list() : List.copyOf(user.getTenants());
        return tenants.stream()
                .sorted(Comparator.comparing(Tenant::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Tenant::getId))
                .map(t -> new TenantOption(t.getId(), t.getName(), t.getWidgetKey()))
                .toList();
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

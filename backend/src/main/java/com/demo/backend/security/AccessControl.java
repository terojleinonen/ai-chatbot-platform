package com.demo.backend.security;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.repository.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authorization checks for the signed-in admin. Roles and tenant assignments are read from the
 * database on every request, so changes take effect immediately without new tokens.
 */
@Component
public class AccessControl {
    private final AdminUserRepository users;

    public AccessControl(AdminUserRepository users) {
        this.users = users;
    }

    public AdminUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        return users.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }

    public AdminUser requireSuperAdmin() {
        AdminUser user = currentUser();
        if (!user.isSuperAdmin()) throw forbidden("Only super admins can do this");
        return user;
    }

    public void requireTenantAccess(Long tenantId) {
        if (tenantId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing tenant id");
        if (!currentUser().canAccessTenant(tenantId)) throw forbidden("You do not have access to this tenant");
    }

    private static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}

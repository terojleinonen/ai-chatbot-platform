package com.demo.backend.service;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.entity.Role;
import com.demo.backend.entity.Tenant;
import com.demo.backend.repository.AdminUserRepository;
import com.demo.backend.repository.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class UserService {
    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72; // BCrypt limit
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,50}");

    private final AdminUserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;

    public UserService(AdminUserRepository users, TenantRepository tenants, PasswordEncoder encoder) {
        this.users = users;
        this.tenants = tenants;
        this.encoder = encoder;
    }

    /** Thrown when the current password given for a password change is wrong. */
    public static class IncorrectPasswordException extends ResponseStatusException {
        public IncorrectPasswordException() {
            super(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public Page<AdminUser> search(String q, Pageable pageable) {
        return users.search(q, pageable);
    }

    public AdminUser findByUsername(String username) {
        return users.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional
    public AdminUser create(String username, String password, Role role, Collection<Long> tenantIds) {
        String name = normalizeUsername(username);
        if (!USERNAME.matcher(name).matches()) {
            throw badRequest("Username must be 3-50 characters: letters, digits, '.', '_' or '-'");
        }
        validatePassword(password);
        if (users.existsByUsername(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        AdminUser user = new AdminUser(name, encoder.encode(password), requireRole(role));
        user.setTenants(resolveTenants(user.getRole(), tenantIds));
        return users.save(user);
    }

    /**
     * Changes another user's role and tenant assignments. Changing your own is refused, so a super admin
     * cannot demote themselves and at least one super admin always remains.
     */
    @Transactional
    public AdminUser updateAccess(Long id, Role role, Collection<Long> tenantIds, String currentUsername) {
        AdminUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getUsername().equals(normalizeUsername(currentUsername))) {
            throw badRequest("You cannot change your own access");
        }
        user.setRole(requireRole(role));
        user.setTenants(resolveTenants(user.getRole(), tenantIds));
        return user;
    }

    private static Role requireRole(Role role) {
        if (role == null) throw badRequest("Role is required (SUPER_ADMIN or TENANT_ADMIN)");
        return role;
    }

    /** Super admins can access every tenant, so they keep no explicit assignments. */
    private Set<Tenant> resolveTenants(Role role, Collection<Long> tenantIds) {
        if (role == Role.SUPER_ADMIN || tenantIds == null || tenantIds.isEmpty()) return new HashSet<>();
        Set<Long> ids = new HashSet<>(tenantIds);
        List<Tenant> found = tenants.findAllById(ids);
        if (found.size() != ids.size()) throw badRequest("Unknown tenant id");
        return new HashSet<>(found);
    }

    /** Sets another user's password. Admins change their own password via {@link #changeOwnPassword}. */
    @Transactional
    public void resetPassword(Long id, String newPassword, String currentUsername) {
        AdminUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getUsername().equals(normalizeUsername(currentUsername))) {
            throw badRequest("Use 'Change my password' to change your own password");
        }
        validatePassword(newPassword);
        user.changePasswordHash(encoder.encode(newPassword));
    }

    @Transactional
    public AdminUser changeOwnPassword(String currentUsername, String currentPassword, String newPassword) {
        AdminUser user = findByUsername(currentUsername);
        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        validatePassword(newPassword);
        user.changePasswordHash(encoder.encode(newPassword));
        return user;
    }

    /** Deleting yourself is refused, which also guarantees at least one admin always remains. */
    @Transactional
    public void delete(Long id, String currentUsername) {
        AdminUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getUsername().equals(normalizeUsername(currentUsername))) {
            throw badRequest("You cannot delete your own account");
        }
        users.delete(user);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw badRequest("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

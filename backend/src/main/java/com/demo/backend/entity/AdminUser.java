package com.demo.backend.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "admin_users")
public class AdminUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored lowercase; usernames are case-insensitive. */
    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt hash, never the plain password. */
    @Column(nullable = false)
    private String passwordHash;

    /**
     * Embedded in issued JWTs and checked on every request. Incrementing it (password change)
     * immediately invalidates the user's existing tokens.
     */
    @Column(nullable = false, columnDefinition = "integer default 0 not null")
    private int tokenVersion;

    private Instant createdAt;

    /** Existing rows (created before roles existed) become super admins, preserving their access. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'SUPER_ADMIN' not null")
    private Role role = Role.TENANT_ADMIN;

    /** Tenants a TENANT_ADMIN may manage. Ignored for SUPER_ADMIN, who can access every tenant. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "admin_user_tenants",
            joinColumns = @JoinColumn(name = "admin_user_id"),
            inverseJoinColumns = @JoinColumn(name = "tenant_id"))
    private Set<Tenant> tenants = new HashSet<>();

    public AdminUser() {}

    public AdminUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Set<Tenant> getTenants() { return tenants; }
    public void setTenants(Set<Tenant> tenants) { this.tenants = tenants; }

    public boolean isSuperAdmin() { return role == Role.SUPER_ADMIN; }

    public boolean canAccessTenant(Long tenantId) {
        return isSuperAdmin() || tenants.stream().anyMatch(t -> t.getId().equals(tenantId));
    }

    /** Sets a new password hash and revokes all tokens issued before this change. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.tokenVersion++;
    }
}

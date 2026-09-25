package com.demo.backend.entity;

import jakarta.persistence.*;

import java.time.Instant;

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

    public AdminUser() {}

    public AdminUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getCreatedAt() { return createdAt; }

    /** Sets a new password hash and revokes all tokens issued before this change. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.tokenVersion++;
    }
}

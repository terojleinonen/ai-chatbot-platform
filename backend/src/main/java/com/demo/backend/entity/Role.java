package com.demo.backend.entity;

public enum Role {
    /** Manages users and tenants; can access every tenant. */
    SUPER_ADMIN,
    /** Can manage only the tenants assigned to them. */
    TENANT_ADMIN
}

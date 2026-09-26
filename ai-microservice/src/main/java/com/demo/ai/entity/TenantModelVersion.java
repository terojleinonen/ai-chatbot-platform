package com.demo.ai.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "tenant_model_versions")
public class TenantModelVersion {
    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false)
    private long version;

    public Long getTenantId() { return tenantId; }
    public long getVersion() { return version; }
}

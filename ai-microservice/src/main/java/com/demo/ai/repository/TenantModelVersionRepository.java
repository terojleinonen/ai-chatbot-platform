package com.demo.ai.repository;

import com.demo.ai.entity.TenantModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TenantModelVersionRepository extends JpaRepository<TenantModelVersion, Long> {
    @Query("select v.version from TenantModelVersion v where v.tenantId = :tenantId")
    Optional<Long> findVersion(@Param("tenantId") Long tenantId);

    /** Atomically creates the tenant's version (1) or increments it (row lock serializes concurrent retrains). */
    @Modifying
    @Query(value = """
            INSERT INTO tenant_model_versions (tenant_id, version) VALUES (:tenantId, 1)
            ON CONFLICT (tenant_id) DO UPDATE SET version = tenant_model_versions.version + 1""", nativeQuery = true)
    void bump(@Param("tenantId") Long tenantId);
}

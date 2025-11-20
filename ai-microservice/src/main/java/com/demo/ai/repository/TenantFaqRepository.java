package com.demo.ai.repository;

import com.demo.ai.entity.TenantFaqEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenantFaqRepository extends JpaRepository<TenantFaqEntity, Long> {
    List<TenantFaqEntity> findByTenantId(Long tenantId);
    void deleteByTenantId(Long tenantId);
}

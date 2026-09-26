package com.demo.backend.repository;

import com.demo.backend.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByWidgetKey(String widgetKey);
}

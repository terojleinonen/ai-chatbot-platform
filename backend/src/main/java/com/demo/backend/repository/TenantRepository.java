package com.demo.backend.repository;

import com.demo.backend.entity.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByWidgetKey(String widgetKey);

    /** Tenants matching the search; restricted to {@code ids} unless {@code allTenants} is true. */
    @Query("""
            select t from Tenant t where (:allTenants = true or t.id in :ids)
              and (:q is null or lower(t.name) like lower(concat('%', cast(:q as string), '%')))""")
    Page<Tenant> search(@Param("allTenants") boolean allTenants, @Param("ids") Collection<Long> ids,
                        @Param("q") String q, Pageable pageable);
}

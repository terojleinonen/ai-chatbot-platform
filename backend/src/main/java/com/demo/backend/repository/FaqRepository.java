package com.demo.backend.repository;

import com.demo.backend.entity.Faq;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FaqRepository extends JpaRepository<Faq, Long> {
    List<Faq> findByTenantId(Long tenantId);
    void deleteByTenantId(Long tenantId);

    @Query("""
            select f from Faq f where f.tenantId = :tenantId and (:q is null
              or lower(f.question) like lower(concat('%', cast(:q as string), '%'))
              or lower(f.answer) like lower(concat('%', cast(:q as string), '%')))""")
    Page<Faq> search(@Param("tenantId") Long tenantId, @Param("q") String q, Pageable pageable);
}

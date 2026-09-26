package com.demo.backend.repository;

import com.demo.backend.entity.AdminUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {
    Optional<AdminUser> findByUsername(String username);
    boolean existsByUsername(String username);

    @Query("select u from AdminUser u where :q is null or u.username like concat('%', lower(cast(:q as string)), '%')")
    Page<AdminUser> search(@Param("q") String q, Pageable pageable);
}

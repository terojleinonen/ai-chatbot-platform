package com.demo.backend.repository;

import com.demo.backend.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {
    Optional<AdminUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<AdminUser> findAllByOrderByUsernameAsc();
}

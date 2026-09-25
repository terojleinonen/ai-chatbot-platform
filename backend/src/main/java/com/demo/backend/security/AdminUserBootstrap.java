package com.demo.backend.security;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first admin account when the admin_users table is empty.
 * Uses ADMIN_USERNAME / ADMIN_PASSWORD; if no password is given, a random one is generated and logged once.
 */
@Component
public class AdminUserBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminUserBootstrap.class);

    private final AdminUserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AdminUserBootstrap(AdminUserRepository users, PasswordEncoder encoder,
                              @Value("${security.admin.username}") String username,
                              @Value("${security.admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        String initial = password;
        if (initial.isBlank()) {
            byte[] random = new byte[12];
            new SecureRandom().nextBytes(random);
            initial = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            log.warn("Created admin user '{}' with generated password: {}  (set ADMIN_PASSWORD to choose one)",
                    username, initial);
        } else {
            log.info("Created admin user '{}' from ADMIN_PASSWORD", username);
        }
        users.save(new AdminUser(username, encoder.encode(initial)));
    }
}

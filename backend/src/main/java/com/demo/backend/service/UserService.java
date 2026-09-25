package com.demo.backend.service;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.repository.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class UserService {
    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72; // BCrypt limit
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,50}");

    private final AdminUserRepository users;
    private final PasswordEncoder encoder;

    public UserService(AdminUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /** Thrown when the current password given for a password change is wrong. */
    public static class IncorrectPasswordException extends ResponseStatusException {
        public IncorrectPasswordException() {
            super(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public List<AdminUser> list() {
        return users.findAllByOrderByUsernameAsc();
    }

    public AdminUser findByUsername(String username) {
        return users.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional
    public AdminUser create(String username, String password) {
        String name = normalizeUsername(username);
        if (!USERNAME.matcher(name).matches()) {
            throw badRequest("Username must be 3-50 characters: letters, digits, '.', '_' or '-'");
        }
        validatePassword(password);
        if (users.existsByUsername(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        return users.save(new AdminUser(name, encoder.encode(password)));
    }

    /** Sets another user's password. Admins change their own password via {@link #changeOwnPassword}. */
    @Transactional
    public void resetPassword(Long id, String newPassword, String currentUsername) {
        AdminUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getUsername().equals(normalizeUsername(currentUsername))) {
            throw badRequest("Use 'Change my password' to change your own password");
        }
        validatePassword(newPassword);
        user.changePasswordHash(encoder.encode(newPassword));
    }

    @Transactional
    public AdminUser changeOwnPassword(String currentUsername, String currentPassword, String newPassword) {
        AdminUser user = findByUsername(currentUsername);
        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        validatePassword(newPassword);
        user.changePasswordHash(encoder.encode(newPassword));
        return user;
    }

    /** Deleting yourself is refused, which also guarantees at least one admin always remains. */
    @Transactional
    public void delete(Long id, String currentUsername) {
        AdminUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getUsername().equals(normalizeUsername(currentUsername))) {
            throw badRequest("You cannot delete your own account");
        }
        users.delete(user);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw badRequest("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

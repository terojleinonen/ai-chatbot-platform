package com.demo.backend.controller;

import com.demo.backend.entity.AdminUser;
import com.demo.backend.security.LoginRateLimiter;
import com.demo.backend.security.TokenService;
import com.demo.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService users;
    private final TokenService tokens;
    private final LoginRateLimiter rateLimiter;

    public UserController(UserService users, TokenService tokens, LoginRateLimiter rateLimiter) {
        this.users = users;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    public record UserDto(Long id, String username, Instant createdAt) {
        static UserDto of(AdminUser u) { return new UserDto(u.getId(), u.getUsername(), u.getCreatedAt()); }
    }
    public record CreateUserRequest(String username, String password) {}
    public record SetPasswordRequest(String password) {}
    public record ChangeOwnPasswordRequest(String currentPassword, String newPassword) {}

    @GetMapping
    public List<UserDto> list() {
        return users.list().stream().map(UserDto::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@RequestBody CreateUserRequest req) {
        return UserDto.of(users.create(req.username(), req.password()));
    }

    /** Resets another admin's password and signs them out everywhere. */
    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id, @RequestBody SetPasswordRequest req,
                              @AuthenticationPrincipal Jwt jwt) {
        users.resetPassword(id, req.password(), jwt.getSubject());
    }

    /**
     * Changes the caller's password. Other sessions are signed out; the caller gets a fresh token.
     * Wrong current passwords count toward the login rate limit.
     */
    @PutMapping("/me/password")
    public ResponseEntity<Map<String, Object>> changeOwnPassword(@RequestBody ChangeOwnPasswordRequest req,
                                                                 @AuthenticationPrincipal Jwt jwt,
                                                                 HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        Optional<Duration> wait = rateLimiter.tryAcquire(ip, jwt.getSubject());
        if (wait.isPresent()) {
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                    .body(Map.of("message", "Too many failed attempts. Try again later.",
                            "retryAfterSeconds", seconds));
        }
        AdminUser user;
        try {
            user = users.changeOwnPassword(jwt.getSubject(), req.currentPassword(), req.newPassword());
        } catch (UserService.IncorrectPasswordException e) {
            throw e; // the attempt reserved above stays counted as a failure
        } catch (RuntimeException e) {
            rateLimiter.recordSuccess(ip, jwt.getSubject()); // e.g. new password too short: not a guess
            throw e;
        }
        rateLimiter.recordSuccess(ip, jwt.getSubject());
        TokenService.IssuedToken issued = tokens.issue(user);
        return ResponseEntity.ok(Map.of("token", issued.token(), "expiresAt", issued.expiresAt().toString(),
                "username", issued.username()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        users.delete(id, jwt.getSubject());
    }
}

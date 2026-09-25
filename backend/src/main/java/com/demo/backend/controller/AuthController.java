package com.demo.backend.controller;

import com.demo.backend.security.LoginRateLimiter;
import com.demo.backend.security.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthenticationManager authManager;
    private final TokenService tokens;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AuthenticationManager authManager, TokenService tokens, LoginRateLimiter rateLimiter) {
        this.authManager = authManager;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        // Behind a reverse proxy, set server.forward-headers-strategy so this is the real client IP.
        String ip = http.getRemoteAddr();
        Optional<Duration> wait = rateLimiter.tryAcquire(ip, req.username());
        if (wait.isPresent()) {
            // Checked before authenticating, so no passwords are tested while blocked.
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                    .body(Map.of("message", "Too many failed login attempts. Try again later.",
                            "retryAfterSeconds", seconds));
        }
        try {
            var auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            rateLimiter.recordSuccess(ip, req.username());
            TokenService.IssuedToken issued = tokens.issue(auth.getName());
            return ResponseEntity.ok(Map.of("token", issued.token(), "expiresAt", issued.expiresAt().toString(),
                    "username", auth.getName()));
        } catch (AuthenticationException e) {
            // The attempt reserved by tryAcquire stays counted as a failure.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid username or password"));
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("username", jwt.getSubject(), "expiresAt", jwt.getExpiresAt().toString());
    }
}

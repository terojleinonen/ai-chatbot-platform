package com.demo.backend.security;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Limits failed logins per (client IP + username) and per client IP with a sliding window.
 * Every attempt is counted before the password is checked and refunded on success, so parallel requests
 * cannot slip past the limit. Implementations: {@link InMemoryLoginRateLimiter} (one instance) and
 * {@link RedisLoginRateLimiter} (shared by all backend instances).
 */
public interface LoginRateLimiter {
    /**
     * Reserves a login attempt. Returns how long to wait if the caller is blocked, or empty if the
     * attempt may proceed (it then counts as a failure unless {@link #recordSuccess} is called).
     */
    Optional<Duration> tryAcquire(String ip, String username);

    /** Refunds the reserved attempt: clears the user's counter for this IP and removes one from the IP's. */
    void recordSuccess(String ip, String username);

    static String userKey(String ip, String username) {
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return "user:" + ip + "|" + user;
    }

    static String ipKey(String ip) {
        return "ip:" + ip;
    }
}

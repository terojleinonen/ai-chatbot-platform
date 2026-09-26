package com.demo.backend.security;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link LoginRateLimiter} (the default, RATE_LIMIT_STORE=memory): correct for a single backend
 * instance. Limits failed logins with a sliding window, per (client IP + username) and per client IP.
 * <p>
 * Every attempt is counted before the password is checked and refunded on success, so parallel
 * requests cannot slip past the limit while the (slow, BCrypt) check runs.
 * Usernames are deliberately not locked globally: that would let anyone lock the real admin out.
 * State is in memory, so each backend instance counts separately and a restart clears it.
 */
public class InMemoryLoginRateLimiter implements LoginRateLimiter {
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final int maxPerUserAndIp;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    public InMemoryLoginRateLimiter(int maxPerUserAndIp, int maxPerIp, Duration window) {
        this(maxPerUserAndIp, maxPerIp, window, Clock.systemUTC());
    }

    InMemoryLoginRateLimiter(int maxPerUserAndIp, int maxPerIp, Duration window, Clock clock) {
        this.maxPerUserAndIp = maxPerUserAndIp;
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Reserves a login attempt. Returns how long to wait if the caller is blocked, or empty if the
     * attempt may proceed (it then counts as a failure unless {@link #recordSuccess} is called).
     */
    @Override
    public synchronized Optional<Duration> tryAcquire(String ip, String username) {
        Instant now = clock.instant();
        String userKey = LoginRateLimiter.userKey(ip, username);
        String ipKey = LoginRateLimiter.ipKey(ip);
        Duration wait = max(blockedFor(userKey, maxPerUserAndIp, now), blockedFor(ipKey, maxPerIp, now));
        if (!wait.isZero()) return Optional.of(wait);
        if (attempts.size() > MAX_TRACKED_KEYS) evictExpired(now);
        attempts.computeIfAbsent(userKey, k -> new ArrayDeque<>()).addLast(now);
        attempts.computeIfAbsent(ipKey, k -> new ArrayDeque<>()).addLast(now);
        return Optional.empty();
    }

    /** Refunds the reserved attempt: clears the user's counter for this IP and removes one from the IP's. */
    @Override
    public synchronized void recordSuccess(String ip, String username) {
        attempts.remove(LoginRateLimiter.userKey(ip, username));
        Deque<Instant> ipAttempts = attempts.get(LoginRateLimiter.ipKey(ip));
        if (ipAttempts != null) {
            ipAttempts.pollLast();
            if (ipAttempts.isEmpty()) attempts.remove(LoginRateLimiter.ipKey(ip));
        }
    }

    private Duration blockedFor(String key, int max, Instant now) {
        Deque<Instant> times = attempts.get(key);
        if (times == null) return Duration.ZERO;
        prune(times, now);
        if (times.isEmpty()) {
            attempts.remove(key);
            return Duration.ZERO;
        }
        if (times.size() < max) return Duration.ZERO;
        // Blocked until enough failures leave the window to get back under the limit.
        Instant freeAt = times.stream().skip(times.size() - max).findFirst().orElseThrow().plus(window);
        return Duration.between(now, freeAt);
    }

    private void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) times.removeFirst();
    }

    private void evictExpired(Instant now) {
        attempts.values().forEach(times -> prune(times, now));
        attempts.values().removeIf(Deque::isEmpty);
    }


    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}

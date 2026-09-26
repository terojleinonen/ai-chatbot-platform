package com.demo.backend.chat;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link ChatRateLimiter} (the default, RATE_LIMIT_STORE=memory): correct for a single backend
 * instance; each instance counts separately and a restart clears it.
 */
public class InMemoryChatRateLimiter implements ChatRateLimiter {
    private static final int MAX_TRACKED_IPS = 100_000;

    private final int maxMessages;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> messages = new HashMap<>();

    public InMemoryChatRateLimiter(int maxMessages, Duration window) {
        this(maxMessages, window, Clock.systemUTC());
    }

    InMemoryChatRateLimiter(int maxMessages, Duration window, Clock clock) {
        this.maxMessages = maxMessages;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquire(String ip) {
        Instant now = clock.instant();
        if (messages.size() > MAX_TRACKED_IPS) evictExpired(now);
        Deque<Instant> times = messages.computeIfAbsent(ip, k -> new ArrayDeque<>());
        prune(times, now);
        if (times.size() >= maxMessages) return false;
        times.addLast(now);
        return true;
    }

    private void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) times.removeFirst();
    }

    private void evictExpired(Instant now) {
        messages.values().forEach(times -> prune(times, now));
        messages.values().removeIf(Deque::isEmpty);
    }
}

package com.demo.backend.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding-window limit on chat messages per client IP, so one client cannot flood the AI service.
 * In memory: each backend instance counts separately and a restart clears it.
 */
@Component
public class ChatRateLimiter {
    private static final int MAX_TRACKED_IPS = 100_000;

    private final int maxMessages;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> messages = new HashMap<>();

    @Autowired
    public ChatRateLimiter(@Value("${chat.rate-limit.max-messages-per-ip}") int maxMessages,
                           @Value("${chat.rate-limit.window}") Duration window) {
        this(maxMessages, window, Clock.systemUTC());
    }

    ChatRateLimiter(int maxMessages, Duration window, Clock clock) {
        this.maxMessages = maxMessages;
        this.window = window;
        this.clock = clock;
    }

    /** Records a message from {@code ip}; returns false if the IP is over its limit (message not counted). */
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

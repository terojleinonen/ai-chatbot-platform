package com.demo.backend.chat;

/**
 * Sliding-window limit on chat messages per client IP, so one client cannot flood the AI service.
 * Implementations: {@link InMemoryChatRateLimiter} (one instance) and {@link RedisChatRateLimiter} (shared).
 */
public interface ChatRateLimiter {
    /** Records a message from {@code ip}; returns false if the IP is over its limit (message not counted). */
    boolean tryAcquire(String ip);
}

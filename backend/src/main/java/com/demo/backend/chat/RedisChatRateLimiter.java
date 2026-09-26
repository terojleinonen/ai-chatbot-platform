package com.demo.backend.chat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** {@link ChatRateLimiter} shared by all backend instances (RATE_LIMIT_STORE=redis); atomic via a Lua script. */
public class RedisChatRateLimiter implements ChatRateLimiter {
    /** KEYS: ip key. ARGV: max messages, window ms, unique member. Returns 1 if allowed (and recorded), else 0. */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 1""", Long.class);

    private final StringRedisTemplate redis;
    private final int maxMessages;
    private final Duration window;

    public RedisChatRateLimiter(StringRedisTemplate redis, int maxMessages, Duration window) {
        this.redis = redis;
        this.maxMessages = maxMessages;
        this.window = window;
    }

    @Override
    public boolean tryAcquire(String ip) {
        Long allowed = redis.execute(ACQUIRE, List.of("ratelimit:chat:" + ip), String.valueOf(maxMessages),
                String.valueOf(window.toMillis()), UUID.randomUUID().toString());
        return allowed != null && allowed == 1L;
    }
}

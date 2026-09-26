package com.demo.backend.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoginRateLimiter} shared by all backend instances (RATE_LIMIT_STORE=redis). Each key is a sorted set of
 * attempt timestamps; one Lua script prunes, checks both limits and records the attempt atomically, using the
 * Redis clock so instances with slightly different clocks agree.
 */
public class RedisLoginRateLimiter implements LoginRateLimiter {
    private static final String PREFIX = "ratelimit:login:";

    /** KEYS: user key, ip key. ARGV: max per user+ip, max per ip, window ms, unique member. Returns wait ms (0 = allowed). */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local window = tonumber(ARGV[3])
            local limits = { tonumber(ARGV[1]), tonumber(ARGV[2]) }
            local wait = 0
            for i = 1, 2 do
              redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - window)
              local n = redis.call('ZCARD', KEYS[i])
              if n >= limits[i] then
                -- blocked until enough attempts leave the window to get back under the limit
                local e = redis.call('ZRANGE', KEYS[i], n - limits[i], n - limits[i], 'WITHSCORES')
                local w = tonumber(e[2]) + window - now
                if w > wait then wait = w end
              end
            end
            if wait > 0 then return wait end
            for i = 1, 2 do
              redis.call('ZADD', KEYS[i], now, ARGV[4])
              redis.call('PEXPIRE', KEYS[i], window)
            end
            return 0""", Long.class);

    /** KEYS: user key, ip key. Clears the user's attempts and removes the newest attempt from the IP's. */
    private static final DefaultRedisScript<Long> REFUND = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('ZPOPMAX', KEYS[2])
            return 1""", Long.class);

    private final StringRedisTemplate redis;
    private final int maxPerUserAndIp;
    private final int maxPerIp;
    private final Duration window;

    public RedisLoginRateLimiter(StringRedisTemplate redis, int maxPerUserAndIp, int maxPerIp, Duration window) {
        this.redis = redis;
        this.maxPerUserAndIp = maxPerUserAndIp;
        this.maxPerIp = maxPerIp;
        this.window = window;
    }

    @Override
    public Optional<Duration> tryAcquire(String ip, String username) {
        Long waitMs = redis.execute(ACQUIRE, keys(ip, username), String.valueOf(maxPerUserAndIp),
                String.valueOf(maxPerIp), String.valueOf(window.toMillis()), UUID.randomUUID().toString());
        return waitMs == null || waitMs <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(waitMs));
    }

    @Override
    public void recordSuccess(String ip, String username) {
        redis.execute(REFUND, keys(ip, username));
    }

    private static List<String> keys(String ip, String username) {
        return List.of(PREFIX + LoginRateLimiter.userKey(ip, username), PREFIX + LoginRateLimiter.ipKey(ip));
    }
}

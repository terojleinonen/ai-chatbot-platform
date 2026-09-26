package com.demo.backend.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** {@link ChatHistory} shared by all backend instances (RATE_LIMIT_STORE=redis): one list per session, with a TTL. */
public class RedisChatHistory implements ChatHistory {
    /** KEYS: session key. ARGV: exchange JSON, max exchanges, ttl ms. */
    private static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1""", Long.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final int maxExchanges;
    private final Duration ttl;

    public RedisChatHistory(StringRedisTemplate redis, int maxExchanges, Duration ttl) {
        this.redis = redis;
        this.maxExchanges = maxExchanges;
        this.ttl = ttl;
    }

    @Override
    public List<Exchange> recent(String sessionKey) {
        if (maxExchanges <= 0) return List.of();
        List<String> stored = redis.opsForList().range(key(sessionKey), 0, -1);
        List<Exchange> exchanges = new ArrayList<>();
        if (stored == null) return exchanges;
        for (String json : stored) {
            try {
                exchanges.add(JSON.readValue(json, Exchange.class));
            } catch (JsonProcessingException e) {
                // Written by another version of the backend; skip it rather than fail the chat.
            }
        }
        return exchanges;
    }

    @Override
    public void append(String sessionKey, Exchange exchange) {
        if (maxExchanges <= 0) return;
        try {
            redis.execute(APPEND, List.of(key(sessionKey)), JSON.writeValueAsString(exchange),
                    String.valueOf(maxExchanges), String.valueOf(ttl.toMillis()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String sessionKey) {
        return "chat:history:" + sessionKey;
    }
}

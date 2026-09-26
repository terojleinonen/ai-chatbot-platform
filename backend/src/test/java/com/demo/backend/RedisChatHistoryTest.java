package com.demo.backend;

import com.demo.backend.chat.ChatHistory.Exchange;
import com.demo.backend.chat.RedisChatHistory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Chat history in a real Redis; two history objects stand in for two backend instances. */
@Testcontainers(disabledWithoutDocker = true)
class RedisChatHistoryTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @Test
    void sharedBetweenInstancesAndCapped() {
        RedisChatHistory a = new RedisChatHistory(redis, 2, Duration.ofMinutes(30));
        RedisChatHistory b = new RedisChatHistory(redis, 2, Duration.ofMinutes(30));
        a.append("1:shared", new Exchange("q1", "a1"));
        b.append("1:shared", new Exchange("q2 \"quoted\" ünicode", "a2"));
        a.append("1:shared", new Exchange("q3", "a3"));
        assertEquals(List.of(new Exchange("q2 \"quoted\" ünicode", "a2"), new Exchange("q3", "a3")), b.recent("1:shared"));
        assertEquals(List.of(), a.recent("1:unknown"));
    }

    @Test
    void expiresAfterTheTtl() {
        RedisChatHistory history = new RedisChatHistory(redis, 10, Duration.ofMinutes(30));
        history.append("1:ttl", new Exchange("q", "a"));
        Long ttl = redis.getExpire("chat:history:1:ttl");
        assertNotNull(ttl);
        assertTrue(ttl > 1700 && ttl <= 1800, "ttl " + ttl);
    }
}

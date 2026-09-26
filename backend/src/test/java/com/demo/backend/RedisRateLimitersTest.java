package com.demo.backend;

import com.demo.backend.chat.RedisChatRateLimiter;
import com.demo.backend.security.RedisLoginRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Redis limiters against a real Redis. Two limiter objects with separate connections stand in for two
 * backend instances sharing one Redis. Runs wherever Docker is available (as in CI).
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimitersTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private static StringRedisTemplate instanceA, instanceB;

    private static StringRedisTemplate connect() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }

    @BeforeAll
    static void connectInstances() {
        instanceA = connect();
        instanceB = connect();
    }

    @AfterAll
    static void close() {
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @BeforeEach
    void flush() {
        instanceA.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void loginLimitIsSharedAcrossInstances() {
        var a = new RedisLoginRateLimiter(instanceA, 5, 20, Duration.ofMinutes(15));
        var b = new RedisLoginRateLimiter(instanceB, 5, 20, Duration.ofMinutes(15));
        for (int i = 0; i < 5; i++) {
            assertTrue((i % 2 == 0 ? a : b).tryAcquire("1.1.1.1", "admin").isEmpty(), "attempt " + (i + 1));
        }
        Duration wait = a.tryAcquire("1.1.1.1", "ADMIN ").orElseThrow();   // same user, normalized
        assertTrue(wait.compareTo(Duration.ofMinutes(14)) > 0 && wait.compareTo(Duration.ofMinutes(15)) <= 0, wait.toString());
        assertTrue(b.tryAcquire("1.1.1.1", "admin").isPresent());
        assertTrue(b.tryAcquire("2.2.2.2", "admin").isEmpty(), "another IP is unaffected");
    }

    @Test
    void perIpLimitCoversManyUsernames() {
        var a = new RedisLoginRateLimiter(instanceA, 5, 20, Duration.ofMinutes(15));
        for (int i = 0; i < 20; i++) assertTrue(a.tryAcquire("3.3.3.3", "user" + i).isEmpty());
        assertTrue(a.tryAcquire("3.3.3.3", "someone-new").isPresent());
    }

    @Test
    void successRefundsTheAttempt() {
        var a = new RedisLoginRateLimiter(instanceA, 3, 20, Duration.ofMinutes(15));
        var b = new RedisLoginRateLimiter(instanceB, 3, 20, Duration.ofMinutes(15));
        a.tryAcquire("4.4.4.4", "admin");
        a.tryAcquire("4.4.4.4", "admin");
        assertTrue(b.tryAcquire("4.4.4.4", "admin").isEmpty());
        b.recordSuccess("4.4.4.4", "admin");                       // refund on the other instance
        for (int i = 0; i < 3; i++) assertTrue(a.tryAcquire("4.4.4.4", "admin").isEmpty());
        assertTrue(a.tryAcquire("4.4.4.4", "admin").isPresent());
    }

    @Test
    void parallelBurstAcrossInstancesAllowsExactlyTheLimit() throws Exception {
        var a = new RedisLoginRateLimiter(instanceA, 5, 20, Duration.ofMinutes(15));
        var b = new RedisLoginRateLimiter(instanceB, 5, 20, Duration.ofMinutes(15));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            var limiter = i % 2 == 0 ? a : b;
            results.add(pool.submit(() -> limiter.tryAcquire("5.5.5.5", "admin").isEmpty()));
        }
        int allowed = 0;
        for (Future<Boolean> f : results) if (f.get(10, TimeUnit.SECONDS)) allowed++;
        pool.shutdown();
        assertEquals(5, allowed);
    }

    @Test
    void windowExpiresAndKeysDoNotLinger() throws Exception {
        var a = new RedisLoginRateLimiter(instanceA, 1, 20, Duration.ofMillis(800));
        assertTrue(a.tryAcquire("6.6.6.6", "admin").isEmpty());
        assertTrue(a.tryAcquire("6.6.6.6", "admin").isPresent());
        Thread.sleep(1000);
        assertTrue(a.tryAcquire("6.6.6.6", "admin").isEmpty());
        Thread.sleep(1000);
        assertTrue(instanceA.keys("ratelimit:*").isEmpty(), "keys expire with the window");
    }

    @Test
    void chatLimitIsSharedAcrossInstances() {
        var a = new RedisChatRateLimiter(instanceA, 3, Duration.ofMinutes(1));
        var b = new RedisChatRateLimiter(instanceB, 3, Duration.ofMinutes(1));
        assertTrue(a.tryAcquire("7.7.7.7"));
        assertTrue(b.tryAcquire("7.7.7.7"));
        assertTrue(a.tryAcquire("7.7.7.7"));
        assertFalse(b.tryAcquire("7.7.7.7"));
        assertFalse(a.tryAcquire("7.7.7.7"));
        assertTrue(b.tryAcquire("8.8.8.8"));
    }
}

package com.demo.backend;

import com.demo.backend.chat.ChatRateLimiter;
import com.demo.backend.chat.RedisChatRateLimiter;
import com.demo.backend.security.LoginRateLimiter;
import com.demo.backend.security.RedisLoginRateLimiter;
import com.demo.backend.service.AiClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The backend with RATE_LIMIT_STORE=redis: Redis-backed limiters are wired in and Redis is in the health check. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "rate-limit.store=redis")
@AutoConfigureMockMvc
class RedisStoreIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisUrl(DynamicPropertyRegistry registry) {
        registry.add("rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired LoginRateLimiter loginLimiter;
    @Autowired ChatRateLimiter chatLimiter;
    @Autowired HealthEndpoint health;
    @Autowired MockMvc mvc;
    @MockBean AiClientService ai;

    @Test
    void usesRedisLimitersAndReportsRedisHealth() throws Exception {
        assertInstanceOf(RedisLoginRateLimiter.class, loginLimiter);
        assertInstanceOf(RedisChatRateLimiter.class, chatLimiter);
        assertEquals(Status.UP, health.healthForPath("redis").getStatus());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"wrong\"}")
                    .with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-password\"}")
                .with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })).andExpect(status().isTooManyRequests());
    }
}

package com.demo.backend.config;

import com.demo.backend.chat.ChatHistory;
import com.demo.backend.chat.ChatRateLimiter;
import com.demo.backend.chat.InMemoryChatHistory;
import com.demo.backend.chat.InMemoryChatRateLimiter;
import com.demo.backend.chat.RedisChatHistory;
import com.demo.backend.chat.RedisChatRateLimiter;
import com.demo.backend.security.InMemoryLoginRateLimiter;
import com.demo.backend.security.LoginRateLimiter;
import com.demo.backend.security.RedisLoginRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Duration;

/**
 * Where the login and chat rate limits keep their counters, and the chat keeps its conversation history: in memory
 * (RATE_LIMIT_STORE=memory, the default; one backend instance) or in Redis (RATE_LIMIT_STORE=redis with REDIS_URL;
 * shared by all instances).
 */
@Configuration
public class RateLimitConfig {
    record Limits(int loginPerUserAndIp, int loginPerIp, Duration loginWindow, int chatPerIp, Duration chatWindow) {}

    record HistorySettings(int maxExchanges, Duration ttl) {}

    @Bean
    HistorySettings chatHistorySettings(@Value("${chat.history.max-exchanges}") int maxExchanges,
                                        @Value("${chat.history.ttl}") Duration ttl) {
        return new HistorySettings(maxExchanges, ttl);
    }

    @Bean
    Limits rateLimits(@Value("${security.login-rate-limit.max-failures-per-user-and-ip}") int loginPerUserAndIp,
                      @Value("${security.login-rate-limit.max-failures-per-ip}") int loginPerIp,
                      @Value("${security.login-rate-limit.window}") Duration loginWindow,
                      @Value("${chat.rate-limit.max-messages-per-ip}") int chatPerIp,
                      @Value("${chat.rate-limit.window}") Duration chatWindow) {
        return new Limits(loginPerUserAndIp, loginPerIp, loginWindow, chatPerIp, chatWindow);
    }

    @Configuration
    @ConditionalOnProperty(name = "rate-limit.store", havingValue = "memory", matchIfMissing = true)
    static class InMemory {
        @Bean
        LoginRateLimiter loginRateLimiter(Limits l) {
            return new InMemoryLoginRateLimiter(l.loginPerUserAndIp(), l.loginPerIp(), l.loginWindow());
        }

        @Bean
        ChatRateLimiter chatRateLimiter(Limits l) {
            return new InMemoryChatRateLimiter(l.chatPerIp(), l.chatWindow());
        }

        @Bean
        ChatHistory chatHistory(HistorySettings h) {
            return new InMemoryChatHistory(h.maxExchanges(), h.ttl());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "rate-limit.store", havingValue = "redis")
    static class Redis {
        /** Also picked up by Spring Boot's Redis health indicator, so /actuator/health reports Redis. */
        @Bean
        LettuceConnectionFactory redisConnectionFactory(@Value("${rate-limit.redis-url:}") String url) {
            if (url.isBlank()) throw new IllegalStateException("REDIS_URL must be set when RATE_LIMIT_STORE=redis");
            RedisConfiguration config = LettuceConnectionFactory.createRedisConfiguration(URI.create(url).toString());
            return new LettuceConnectionFactory(config);
        }

        @Bean
        StringRedisTemplate rateLimitRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean
        LoginRateLimiter loginRateLimiter(StringRedisTemplate redis, Limits l) {
            return new RedisLoginRateLimiter(redis, l.loginPerUserAndIp(), l.loginPerIp(), l.loginWindow());
        }

        @Bean
        ChatRateLimiter chatRateLimiter(StringRedisTemplate redis, Limits l) {
            return new RedisChatRateLimiter(redis, l.chatPerIp(), l.chatWindow());
        }

        @Bean
        ChatHistory chatHistory(StringRedisTemplate redis, HistorySettings h) {
            return new RedisChatHistory(redis, h.maxExchanges(), h.ttl());
        }
    }
}

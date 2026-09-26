package com.demo.backend.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ChatRateLimiterTest {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final ChatRateLimiter limiter = new ChatRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void limitsPerIpWithinTheWindow() {
        for (int i = 0; i < 3; i++) assertTrue(limiter.tryAcquire("1.1.1.1"));
        assertFalse(limiter.tryAcquire("1.1.1.1"));
        assertTrue(limiter.tryAcquire("2.2.2.2"), "other IPs are unaffected");
    }

    @Test
    void windowSlides() {
        assertTrue(limiter.tryAcquire("1.1.1.1"));
        clock.now = clock.now.plusSeconds(30);
        assertTrue(limiter.tryAcquire("1.1.1.1"));
        assertTrue(limiter.tryAcquire("1.1.1.1"));
        assertFalse(limiter.tryAcquire("1.1.1.1"));
        clock.now = clock.now.plusSeconds(31); // first message left the window
        assertTrue(limiter.tryAcquire("1.1.1.1"));
        assertFalse(limiter.tryAcquire("1.1.1.1"));
    }

    @Test
    void rejectedMessagesDoNotExtendTheBlock() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("1.1.1.1");
        for (int i = 0; i < 10; i++) assertFalse(limiter.tryAcquire("1.1.1.1"));
        clock.now = clock.now.plusSeconds(61);
        assertTrue(limiter.tryAcquire("1.1.1.1"));
    }
}

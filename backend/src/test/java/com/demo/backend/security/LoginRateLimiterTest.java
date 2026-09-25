package com.demo.backend.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {
    /** Clock the test can move forward. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(3, 5, Duration.ofMinutes(15), clock);

    private void fail(String ip, String user, int times) {
        for (int i = 0; i < times; i++) {
            assertTrue(limiter.tryAcquire(ip, user).isEmpty(), "attempt " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void blocksUserAndIpAfterMaxFailures() {
        fail("1.1.1.1", "admin", 3);
        var wait = limiter.tryAcquire("1.1.1.1", "admin");
        assertTrue(wait.isPresent());
        assertEquals(Duration.ofMinutes(15), wait.get());
    }

    @Test
    void usernameMatchingIsCaseInsensitive() {
        fail("1.1.1.1", "admin", 2);
        fail("1.1.1.1", " ADMIN ", 1);
        assertTrue(limiter.tryAcquire("1.1.1.1", "Admin").isPresent());
    }

    @Test
    void otherIpIsNotAffected() {
        fail("1.1.1.1", "admin", 3);
        assertTrue(limiter.tryAcquire("2.2.2.2", "admin").isEmpty());
    }

    @Test
    void blockExpiresAsTheWindowSlides() {
        fail("1.1.1.1", "admin", 1);
        clock.advance(Duration.ofMinutes(10));
        fail("1.1.1.1", "admin", 2);
        assertEquals(Duration.ofMinutes(5), limiter.tryAcquire("1.1.1.1", "admin").orElseThrow());
        clock.advance(Duration.ofMinutes(5));
        assertTrue(limiter.tryAcquire("1.1.1.1", "admin").isEmpty(), "oldest failure left the window");
    }

    @Test
    void ipLimitCoversManyUsernames() {
        fail("1.1.1.1", "a", 2);
        fail("1.1.1.1", "b", 2);
        fail("1.1.1.1", "c", 1);
        assertTrue(limiter.tryAcquire("1.1.1.1", "d").isPresent(), "5 failures from one IP across users");
    }

    @Test
    void successRefundsTheAttempt() {
        fail("1.1.1.1", "admin", 2);
        assertTrue(limiter.tryAcquire("1.1.1.1", "admin").isEmpty());
        limiter.recordSuccess("1.1.1.1", "admin");
        fail("1.1.1.1", "admin", 3); // user counter was cleared
        assertTrue(limiter.tryAcquire("1.1.1.1", "admin").isPresent());
    }

    @Test
    void successDoesNotResetIpCounterForOtherUsers() {
        fail("1.1.1.1", "x", 3);
        fail("1.1.1.1", "y", 1);
        assertTrue(limiter.tryAcquire("1.1.1.1", "admin").isEmpty());
        limiter.recordSuccess("1.1.1.1", "admin");
        fail("1.1.1.1", "z", 1);
        assertTrue(limiter.tryAcquire("1.1.1.1", "z").isPresent(), "IP still has 5 failures");
    }
}

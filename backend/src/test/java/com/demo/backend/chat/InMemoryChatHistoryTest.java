package com.demo.backend.chat;

import com.demo.backend.chat.ChatHistory.Exchange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryChatHistoryTest {
    private final ChatRateLimiterTest.TestClock clock = new ChatRateLimiterTest.TestClock();
    private final InMemoryChatHistory history = new InMemoryChatHistory(2, Duration.ofMinutes(30), clock);

    private static Exchange ex(int i) {
        return new Exchange("q" + i, "a" + i);
    }

    @Test
    void keepsTheMostRecentExchangesPerSession() {
        history.append("1:s", ex(1));
        history.append("1:s", ex(2));
        history.append("1:s", ex(3));
        history.append("2:s", ex(9));
        assertEquals(List.of(ex(2), ex(3)), history.recent("1:s"));
        assertEquals(List.of(ex(9)), history.recent("2:s"));
        assertEquals(List.of(), history.recent("1:other"));
    }

    @Test
    void sessionsExpireAfterTheTtlWithoutMessages() {
        history.append("1:s", ex(1));
        clock.now = clock.now.plus(Duration.ofMinutes(29));
        history.append("1:s", ex(2));                       // restarts the expiry
        clock.now = clock.now.plus(Duration.ofMinutes(29));
        assertEquals(List.of(ex(1), ex(2)), history.recent("1:s"));
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertEquals(List.of(), history.recent("1:s"));
        history.append("1:s", ex(3));                       // an expired session starts over
        assertEquals(List.of(ex(3)), history.recent("1:s"));
    }

    @Test
    void expiredSessionsAreRemovedFromMemory() {
        history.append("1:a", ex(1));
        history.append("1:b", ex(1));
        clock.now = clock.now.plus(Duration.ofHours(1));
        history.append("1:c", ex(1));
        assertEquals(1, history.size());
    }

    @Test
    void zeroMaxExchangesDisablesHistory() {
        InMemoryChatHistory disabled = new InMemoryChatHistory(0, Duration.ofMinutes(30), clock);
        disabled.append("1:s", ex(1));
        assertEquals(List.of(), disabled.recent("1:s"));
    }
}

package com.demo.backend.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ChatHistory} for a single backend instance (RATE_LIMIT_STORE=memory). */
public class InMemoryChatHistory implements ChatHistory {
    private record Session(Deque<Exchange> exchanges, Instant lastUsed) {}

    private final int maxExchanges;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile Instant lastSweep;

    public InMemoryChatHistory(int maxExchanges, Duration ttl) {
        this(maxExchanges, ttl, Clock.systemUTC());
    }

    InMemoryChatHistory(int maxExchanges, Duration ttl, Clock clock) {
        this.maxExchanges = maxExchanges;
        this.ttl = ttl;
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    @Override
    public List<Exchange> recent(String sessionKey) {
        Session session = sessions.get(sessionKey);
        if (session == null || expired(session, clock.instant())) return List.of();
        synchronized (session.exchanges()) {
            return List.copyOf(session.exchanges());
        }
    }

    @Override
    public void append(String sessionKey, Exchange exchange) {
        if (maxExchanges <= 0) return;
        Instant now = clock.instant();
        sweep(now);
        sessions.compute(sessionKey, (key, old) -> {
            Deque<Exchange> exchanges = old == null || expired(old, now) ? new ArrayDeque<>() : old.exchanges();
            synchronized (exchanges) {
                exchanges.addLast(exchange);
                while (exchanges.size() > maxExchanges) exchanges.removeFirst();
            }
            return new Session(exchanges, now);
        });
    }

    int size() {
        return sessions.size();
    }

    private boolean expired(Session session, Instant now) {
        return !session.lastUsed().plus(ttl).isAfter(now);
    }

    /** Drops expired sessions, at most once a minute, so memory is bounded by the sessions active within the TTL. */
    private void sweep(Instant now) {
        if (now.isBefore(lastSweep.plusSeconds(60))) return;
        lastSweep = now;
        sessions.values().removeIf(session -> expired(session, now));
    }
}

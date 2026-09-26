package com.demo.backend.chat;

import java.util.List;

/**
 * The recent question/answer exchanges of each chat session, sent to the AI with every new question so it can
 * understand follow-ups ("and on weekends?"). Sessions are keyed by tenant and the client's random session id,
 * and forgotten after a period without messages.
 */
public interface ChatHistory {
    record Exchange(String question, String answer) {}

    /** The session's most recent exchanges, oldest first (empty for a new or expired session). */
    List<Exchange> recent(String sessionKey);

    /** Records an exchange, keeping only the most recent ones, and restarts the session's expiry. */
    void append(String sessionKey, Exchange exchange);
}

package com.demo.backend.chat;

import com.demo.backend.service.Cancellation;

import java.util.Map;
import java.util.function.Consumer;

/**
 * One reply on its way to a chat session: streams deltas, then exactly one final message, either the complete reply
 * ({@link #finish}) or, when the visitor stops it, the text shown so far ({@link #stop}). After the final message
 * nothing more is sent.
 */
public class ChatReply {
    private final String sessionId;
    private final String replyId;
    private final Consumer<Map<String, Object>> sender;
    private final Cancellation cancellation = new Cancellation();
    private final StringBuilder streamed = new StringBuilder();
    private boolean ended;
    private boolean stopped;

    public ChatReply(String sessionId, String replyId, Consumer<Map<String, Object>> sender) {
        this.sessionId = sessionId;
        this.replyId = replyId;
        this.sender = sender;
    }

    /** Stops the AI call producing this reply. */
    public Cancellation cancellation() {
        return cancellation;
    }

    public synchronized void delta(String text) {
        if (ended) return;
        streamed.append(text);
        sender.accept(Map.of("sessionId", sessionId, "replyId", replyId, "delta", text));
    }

    /** Sends the complete reply, unless the reply was already stopped. */
    public synchronized void finish(String reply) {
        if (ended) return;
        ended = true;
        sender.accept(Map.of("sessionId", sessionId, "replyId", replyId, "reply", reply, "done", true));
    }

    /** Ends the reply with the text streamed so far and stops the AI call. */
    public void stop() {
        synchronized (this) {
            if (ended) return;
            ended = stopped = true;
            sender.accept(Map.of("sessionId", sessionId, "replyId", replyId, "reply", streamed.toString(),
                    "done", true, "stopped", true));
        }
        cancellation.cancel();
    }

    public synchronized boolean isStopped() {
        return stopped;
    }

    /** The text sent as deltas so far. */
    public synchronized String streamedText() {
        return streamed.toString();
    }
}

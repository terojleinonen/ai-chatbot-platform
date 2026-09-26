package com.demo.ai.llm;

/** Claude responded, but the reply cannot be shown to the customer (refused, cut off, or empty). */
public class LlmUnusableReplyException extends RuntimeException {
    public LlmUnusableReplyException(String message) {
        super(message);
    }
}

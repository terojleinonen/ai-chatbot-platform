package com.demo.ai.llm;

import com.anthropic.errors.AnthropicException;
import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.model.TenantModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real SDK against a local stand-in for the Messages API, so the request and response wire format is tested. */
class ClaudeResponderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TenantModel FAQS = new TenantModel(List.of(
            new TenantFaqEntity(1L, "What are your opening hours?", "9-17 on weekdays."),
            new TenantFaqEntity(1L, "Do you ship internationally?", "Yes, to most countries.")));

    private HttpServer server;
    private volatile JsonNode lastRequest;
    private volatile int status = 200;
    private volatile String responseBody;
    private final AtomicInteger calls = new AtomicInteger();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            lastRequest = JSON.readTree(exchange.getRequestBody());
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ClaudeResponder responder(int maxContextChars) {
        return new ClaudeResponder("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "claude-opus-5", "low", 2048, maxContextChars, Duration.ofSeconds(5), 0, metrics);
    }

    private void reply(String text, String stopReason) {
        responseBody = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":%s}],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":12,"output_tokens":7,"cache_creation_input_tokens":300,"cache_read_input_tokens":0}}
                """.formatted(JSON.valueToTree(text).toString(), stopReason);
    }

    @Test
    void answersFromTheFaqsWithACachedSystemPrompt() {
        reply("We're open 9-17 on weekdays.", "end_turn");
        ClaudeResponder.Reply r = responder(24000).answer(FAQS, "when are you open?");

        assertEquals(new ClaudeResponder.Reply("We're open 9-17 on weekdays.", true), r);
        assertEquals("claude-opus-5", lastRequest.get("model").asText());
        assertEquals("low", lastRequest.at("/output_config/effort").asText());
        assertEquals(ClaudeResponder.INSTRUCTIONS, lastRequest.at("/system/0/text").asText());
        String faqBlock = lastRequest.at("/system/1/text").asText();
        assertTrue(faqBlock.contains("<question>Do you ship internationally?</question>"), faqBlock);
        assertEquals("ephemeral", lastRequest.at("/system/1/cache_control/type").asText());
        assertEquals("user", lastRequest.at("/messages/0/role").asText());
        assertEquals("when are you open?", lastRequest.at("/messages/0/content").asText());
        assertEquals(300, metrics.counter("ai.llm.tokens", "model", "claude-opus-5", "type", "cache_write").count());
        assertEquals(1, metrics.timer("ai.llm.requests", "model", "claude-opus-5", "outcome", "answered").count());
    }

    @Test
    void questionsTheFaqsDoNotCoverAreNotAnswered() {
        reply("NO_ANSWER", "end_turn");
        assertEquals(new ClaudeResponder.Reply(TenantModel.NO_MATCH, false), responder(24000).answer(FAQS, "what's your CEO's name?"));
    }

    @Test
    void largeFaqSetsSendOnlyTheMostRelevantFaqsWithoutCaching() {
        reply("Yes, to most countries.", "end_turn");
        // Room for one FAQ only (45 and 51 characters).
        responder(60).answer(FAQS, "do you ship internationally?");
        String faqBlock = lastRequest.at("/system/1/text").asText();
        assertTrue(faqBlock.contains("Do you ship internationally?"), faqBlock);
        assertFalse(faqBlock.contains("opening hours"), faqBlock);
        assertTrue(lastRequest.at("/system/1/cache_control").isMissingNode());
    }

    @Test
    void refusedOrTruncatedRepliesAreNotShown() {
        reply("", "refusal");
        assertThrows(LlmUnusableReplyException.class, () -> responder(24000).answer(FAQS, "hi"));
        reply("We are open from", "max_tokens");
        assertThrows(LlmUnusableReplyException.class, () -> responder(24000).answer(FAQS, "hi"));
    }

    @Test
    void apiErrorsAreReported() {
        status = 529;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        assertThrows(AnthropicException.class, () -> responder(24000).answer(FAQS, "hi"));
        assertEquals(1, calls.get());   // max-retries 0
        assertEquals(1, metrics.timer("ai.llm.requests", "model", "claude-opus-5", "outcome", "error").count());
    }

    @Test
    void disabledWithoutAnApiKey() {
        assertFalse(new ClaudeResponder("", "", "claude-opus-5", "low", 2048, 24000, Duration.ofSeconds(5), 0, metrics).enabled());
        assertTrue(responder(24000).enabled());
    }
}

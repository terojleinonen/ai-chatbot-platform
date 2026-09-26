package com.demo.ai.llm;

import com.anthropic.errors.AnthropicException;
import com.demo.ai.dto.ChatExchange;
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
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
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

    /** A streamed Messages API response (server-sent events) writing {@code chunks} as text deltas. */
    private void reply(String stopReason, String... chunks) {
        String usage = "{\"input_tokens\":12,\"output_tokens\":7,\"cache_creation_input_tokens\":300,\"cache_read_input_tokens\":0}";
        StringBuilder sse = new StringBuilder();
        event(sse, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5\",\"content\":[],\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":" + usage + "}}");
        event(sse, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        for (String chunk : chunks) {
            event(sse, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
                    + JSON.valueToTree(chunk) + "}}");
        }
        event(sse, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
        event(sse, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":" + usage + "}");
        event(sse, "message_stop", "{\"type\":\"message_stop\"}");
        responseBody = sse.toString();
    }

    private static void event(StringBuilder sse, String name, String data) {
        sse.append("event: ").append(name).append("\ndata: ").append(data).append("\n\n");
    }

    private final List<String> streamed = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ClaudeResponder.Reply ask(ClaudeResponder responder, String question, List<ChatExchange> history) {
        return responder.answer(FAQS, question, history, streamed::add);
    }

    @Test
    void answersFromTheFaqsWithACachedSystemPrompt() {
        reply("end_turn", "We're open 9-17 on weekdays.");
        ClaudeResponder.Reply r = ask(responder(24000), "when are you open?", List.of());

        assertEquals(new ClaudeResponder.Reply("We're open 9-17 on weekdays.", true), r);
        assertTrue(lastRequest.get("stream").asBoolean());
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
        reply("end_turn", "NO_ANSWER");
        assertEquals(new ClaudeResponder.Reply(TenantModel.NO_MATCH, false), ask(responder(24000), "what's your CEO's name?", List.of()));
    }

    @Test
    void largeFaqSetsSendOnlyTheMostRelevantFaqsWithoutCaching() {
        reply("end_turn", "Yes, to most countries.");
        // Room for one FAQ only (45 and 51 characters).
        ask(responder(60), "do you ship internationally?", List.of());
        String faqBlock = lastRequest.at("/system/1/text").asText();
        assertTrue(faqBlock.contains("Do you ship internationally?"), faqBlock);
        assertFalse(faqBlock.contains("opening hours"), faqBlock);
        assertTrue(lastRequest.at("/system/1/cache_control").isMissingNode());
    }

    @Test
    void refusedOrTruncatedRepliesAreNotShown() {
        reply("refusal", "");
        assertThrows(LlmUnusableReplyException.class, () -> ask(responder(24000), "hi", List.of()));
        reply("max_tokens", "We are open from");
        assertThrows(LlmUnusableReplyException.class, () -> ask(responder(24000), "hi", List.of()));
    }

    @Test
    void apiErrorsAreReported() {
        status = 529;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        assertThrows(AnthropicException.class, () -> ask(responder(24000), "hi", List.of()));
        assertEquals(1, calls.get());   // max-retries 0
        assertEquals(1, metrics.timer("ai.llm.requests", "model", "claude-opus-5", "outcome", "error").count());
    }

    @Test
    void disabledWithoutAnApiKey() {
        assertFalse(new ClaudeResponder("", "", "claude-opus-5", "low", 2048, 24000, Duration.ofSeconds(5), 0, metrics).enabled());
        assertTrue(responder(24000).enabled());
    }

    @Test
    void earlierExchangesComeBeforeTheQuestion() {
        reply("end_turn", "We're closed on weekends.");
        List<ChatExchange> history = List.of(new ChatExchange("what are your opening hours?", "9-17 on weekdays."));
        ask(responder(24000), "and on weekends?", history);

        JsonNode messages = lastRequest.get("messages");
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("what are your opening hours?", messages.get(0).get("content").asText());
        assertEquals("assistant", messages.get(1).get("role").asText());
        assertEquals("9-17 on weekdays.", messages.get(1).get("content").asText());
        assertEquals("user", messages.get(2).get("role").asText());
        assertEquals("and on weekends?", messages.get(2).get("content").asText());
    }

    @Test
    void followUpsFindTheFaqsOfTheConversationInLargeFaqSets() {
        reply("end_turn", "Yes.");
        // Room for one FAQ: "which countries?" alone shares no words with either FAQ question.
        ask(responder(60), "which countries?",
                List.of(new ChatExchange("do you ship internationally?", "Yes, to most countries.")));
        assertTrue(lastRequest.at("/system/1/text").asText().contains("Do you ship internationally?"));
    }

    @Test
    void textIsStreamedAsClaudeWritesIt() {
        reply("end_turn", "We're open ", "9-17 ", "on weekdays.");
        assertEquals(new ClaudeResponder.Reply("We're open 9-17 on weekdays.", true), ask(responder(24000), "hours?", List.of()));
        assertEquals(List.of("We're open ", "9-17 ", "on weekdays."), streamed);
    }

    @Test
    void theNoAnswerMarkerIsNeverStreamed() {
        reply("end_turn", "NO", "_ANS", "WER");
        assertEquals(new ClaudeResponder.Reply(TenantModel.NO_MATCH, false), ask(responder(24000), "CEO?", List.of()));
        assertEquals(List.of(), streamed);
    }

    @Test
    void textThatOnlyStartsLikeTheMarkerIsReleased() {
        List<String> out = new java.util.ArrayList<>();
        ClaudeResponder.NoAnswerFilter filter = new ClaudeResponder.NoAnswerFilter(out::add);
        filter.accept("NO");
        filter.accept("T");
        filter.accept(" yet.");
        assertEquals(List.of("NOT", " yet."), out);
    }

    @Test
    void errorsInTheMiddleOfTheStreamAreReported() {
        responseBody = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n";
        assertThrows(AnthropicException.class, () -> ask(responder(24000), "hi", List.of()));
    }
}
